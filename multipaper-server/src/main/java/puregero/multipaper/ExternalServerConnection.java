package puregero.multipaper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.haproxy.*;
import io.netty.util.internal.SystemPropertyUtil;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.externalserverprotocol.*;
import puregero.multipaper.mastermessagingprotocol.MessageBootstrap;
import puregero.multipaper.mastermessagingprotocol.MessageLengthDecoder;
import puregero.multipaper.mastermessagingprotocol.MessageLengthEncoder;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ExternalServerConnection extends ChannelInitializer<SocketChannel> implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger(ExternalServerConnection.class.getSimpleName());

    private Channel channel;
    public long nanoTime = 0;
    public ExternalServer externalServer = null;
    public final CompletableFuture<Void> onConnect = new CompletableFuture<>();
    public HashSet<String> subscribedWorlds = new HashSet<>();
    private static final Queue<List<ExternalPlayer>> externalPlayerListPool = new LinkedList<>();
    private final HashMap<Packet<?>, List<ExternalPlayer>> packetsToSend = new LinkedHashMap<>();
    public final ConcurrentHashMap<ChunkKey, Consumer<DataInputStream>> chunkCallbacks = new ConcurrentHashMap<>();
    public long lastPacketSent = 0;
    public long lastPacketReceived = 0;
    public final ConcurrentHashMap<ChunkKey, Consumer<DataInputStream>> entitiesCallbacks = new ConcurrentHashMap<>();

    public ExternalServerConnection() {

    }

    public ExternalServerConnection(Channel channel) {
        this.channel = channel;
        setupPipeline();
        nanoTime = System.nanoTime();
        this.channel.writeAndFlush(new HelloPacket(MultiPaperConfiguration.get().masterConnection.myName, nanoTime, getSupportedCompressionFlags()));
    }

    public int getSupportedCompressionFlags() {
        int supportedCompressionFlags = SetCompressionPacket.ZLIB_COMPRESSION;
        if (Zstd.isAvailable()) {
            supportedCompressionFlags |= SetCompressionPacket.ZSTD_COMPRESSION;
        }
        return supportedCompressionFlags;
    }

    @Override
    public void initChannel(SocketChannel channel) {
        this.channel = channel;
    }

    public void sendMinecraftHandshake(String address, String secret, int port) {
        // MultiPaper start - Add support for Proxy Protocol
        if (io.papermc.paper.configuration.GlobalConfiguration.get() == null) {
            // Paper config hasn't been loaded yet, try again 100ms later
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> sendMinecraftHandshake(address, secret, port));
            return;
        }

        ChannelFuture future2 = channel.newSucceededFuture();

        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.proxyProtocol) {
            channel.pipeline().addLast("haproxy-encoder", HAProxyMessageEncoder.INSTANCE);
            future2 = channel.writeAndFlush(new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.LOCAL, HAProxyProxiedProtocol.UNKNOWN, null, null, 0, 0));
        }

        future2.addListener(future1 -> {
        // MultiPaper end

        channel.pipeline()
                .addLast("prepender", new MessageLengthEncoder())
                .addLast("encoder", new PacketEncoder(PacketFlow.SERVERBOUND));
        channel.attr(Connection.ATTRIBUTE_PROTOCOL).set(ConnectionProtocol.HANDSHAKING);
        channel.writeAndFlush(new ClientIntentionPacket(address + "\00" + secret, port, ConnectionProtocol.STATUS))
                .addListener(future -> {
                    if (future.isSuccess()) {
                        setupPipeline();
                    } else if (future.cause() != null) {
                        future.cause().printStackTrace();
                    }
                });

        }); // MultiPaper - Add support for Proxy Protocol
    }

    public void setupPipeline() {
        // Let's yeet minecraft's networking out of here
        while (channel.pipeline().last() != null) {
            channel.pipeline().removeLast();
        }

        // And add our own
        if (!Boolean.getBoolean("Paper.disableFlushConsolidate")) channel.pipeline().addFirst(new io.netty.handler.flush.FlushConsolidationHandler());

        com.velocitypowered.natives.compression.VelocityCompressor compressor = com.velocitypowered.natives.util.Natives.compress.get().create(-1);
        channel.pipeline()
                .addLast("packetsplitter", new MessageLengthDecoder())
                .addLast("decoder", new ExternalServerPacketDecoder())
                .addLast("packetprepender", new MessageLengthEncoder())
                .addLast("encoder", new ExternalServerPacketEncoder())
                .addLast("packet_handler", new ExternalServerPacketHandler(this));

        // And put it onto our own event loop
        if (MessageBootstrap.getEventLoopGroup() != channel.eventLoop().parent()) {
            if (SystemPropertyUtil.getBoolean("multipaper.netty.useOwnEventLoop", true)) {
                channel.deregister().addListener((ChannelFutureListener) future -> {
                    MessageBootstrap.getEventLoopGroup().register(future.channel()).sync();
                });
            } else {
                LOGGER.info("Using Minecraft's event loop");
            }
        }

        // And when this closes
        channel.closeFuture().addListener(future -> {
            if (future.isDone()) {
                for (ServerPlayer player : DedicatedServer.getServer().getPlayerList().players) {
                    if (player instanceof ExternalPlayer && ((ExternalPlayer) player).externalServerConnection == this) {
                        MultiPaper.runSync(() -> player.connection.disconnect("External server disconnected"));
                    }
                }
            }
        });
    }

    public Channel getChannel() {
        return channel;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    public void send(ExternalServerPacket packet) {
        if (!channel.isOpen()) {
            new IOException("Channel is closed for " + externalServer.getName()).printStackTrace();
        } else {
            onConnect.thenRun(() -> {
                if (channel.eventLoop().inEventLoop()) {
                    lastPacketSent = System.currentTimeMillis();
                    channel.writeAndFlush(packet);
                } else {
                    lastPacketSent = System.currentTimeMillis();
                    channel.eventLoop().execute(() -> channel.writeAndFlush(packet));
                }
            });
        }
    }

    public void tick() {
        // Send the packets after each vanilla tick
        synchronized (packetsToSend) {
            packetsToSend.forEach((packet, players) -> {
                send(new SendPacketPacket(players, packet));
                players.clear();
                externalPlayerListPool.add(players);
            });
            packetsToSend.clear();
        }
    }

    public void sendPacket(ExternalPlayer player, Packet<?> packet) {
        // Combine all the players that the packet's being sent to together
        // so that the packet only needs to be sent to the external server
        // just once, not duplicated for each player
        synchronized (packetsToSend) {
            List<ExternalPlayer> players = packetsToSend.computeIfAbsent(packet, key -> {
                List<ExternalPlayer> list = externalPlayerListPool.poll();
                if (list == null) {
                    list = new ArrayList<>();
                }
                return list;
            });
            if (players.contains(player)) {
                // Duplicate packet with the same message, flush the old one to maintain sending order
                tick();
                sendPacket(player, packet);
                return;
            }
            players.add(player);
        }
    }

    public void requestChunk(String world, int cx, int cz, Consumer<DataInputStream> callback) {
        if (callback != null) {
            if (chunkCallbacks.put(new ChunkKey(world, cx, cz), callback) != null) {
                LOGGER.warn("A chunk callback already existed for " + world + ", " + cx + ", " + cz + " (new request is to " + externalServer.getName() + ")");
                LOGGER.warn("Stats for " + externalServer.getName() + ": last packet sent=" + (System.currentTimeMillis() - lastPacketSent) + "ms ago; last packet received=" + (System.currentTimeMillis() - lastPacketReceived) + "ms ago");
            }
        }

        RequestChunkPacket.blocker = externalServer;
        send(new RequestChunkPacket(world, cx, cz));
    }

    public void requestEntities(String world, int cx, int cz, Consumer<DataInputStream> callback) {
        if (callback != null) {
            if (entitiesCallbacks.put(new ChunkKey(world, cx, cz), callback) != null) {
                LOGGER.warn("An entities callback already existed for " + world + ", " + cx + ", " + cz + " (new request is to " + externalServer.getName() + ")");
                LOGGER.warn("Stats for " + externalServer.getName() + ": last packet sent=" + (System.currentTimeMillis() - lastPacketSent) + "ms ago; last packet received=" + (System.currentTimeMillis() - lastPacketReceived) + "ms ago");
            }
        }

        send(new RequestEntitiesPacket(world, cx, cz));
    }
}
