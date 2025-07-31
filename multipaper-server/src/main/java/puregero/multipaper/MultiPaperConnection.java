package puregero.multipaper;

import com.google.common.collect.Maps;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import io.papermc.paper.util.MCUtil;
import io.papermc.paper.world.ChunkEntitySlices;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.externalserverprotocol.EntityRemovePacket;
import puregero.multipaper.externalserverprotocol.SendChunkPacket;
import puregero.multipaper.externalserverprotocol.EntityUpdateNBTPacket;
import puregero.multipaper.externalserverprotocol.EntityUpdateWithDependenciesPacket;
import puregero.multipaper.externalserverprotocol.SendEntitiesPacket;
import puregero.multipaper.externalserverprotocol.SendTickListPacket;
import puregero.multipaper.mastermessagingprotocol.MessageBootstrap;
import puregero.multipaper.mastermessagingprotocol.datastream.InboundDataStream;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.*;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@ChannelHandler.Sharable
public class MultiPaperConnection extends ServerBoundMessageHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperConnection.class.getSimpleName());
    private static final UUID thisServersUuid = UUID.randomUUID();
    private static final long PING_TIMEOUT = Long.getLong("multipaper.master.pingtimeout", 60 * 1000L);

    private final String myName;
    private final MessageBootstrap<ServerBoundMessage, MasterBoundMessage> bootstrap;
    private SocketChannel channel;
    private boolean channelActive = false;
    private long lastPingReceived;
    private final Set<MasterBoundMessage> unhandledRequests = ConcurrentHashMap.newKeySet();
    private final Map<String, ExternalServer> serversMap = new ConcurrentHashMap<>();
    public String secret;
    public Map<String, byte[]> dataCache = Maps.newHashMap();
    public int port = -1;

    public MultiPaperConnection() {
        System.setProperty("multipaper.netty.threads", System.getProperty("multipaper.netty.threads", Integer.toString(Math.min(Runtime.getRuntime().availableProcessors(), 3))));
        myName = MultiPaperConfiguration.get().masterConnection.myName;
        bootstrap = new MessageBootstrap<>(new ServerBoundProtocol(), new MasterBoundProtocol(), channel -> channel.pipeline().addLast(this));
        MessageBootstrap.getEventLoopGroup().scheduleAtFixedRate(this::ping, 0, PING_TIMEOUT / 4, TimeUnit.MILLISECONDS);
        connect();
    }

    public void connect() {
        String server = MultiPaperConfiguration.get().masterConnection.masterAddress;
        LOGGER.info("Connecting to " + server + "...");
        String[] serverParts = server.split(":");
        bootstrap.connectTo(serverParts[0], Integer.parseInt(serverParts[1])).addListener(future -> {
            if (future.cause() != null) {
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(this::connect);
            }
        });
    }

    public Channel getChannel() {
        return channel;
    }

    public ExternalServer getOrCreateServer(String name) {
        return serversMap.computeIfAbsent(name, key -> new ExternalServer(key, key.equals(myName)));
    }

    public ExternalServer getMe() {
        return getOrCreateServer(myName);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = (SocketChannel) ctx.channel();
        LOGGER.info("Connected to " + channel.remoteAddress());
        channel.write(new HelloMessage(myName, thisServersUuid));

        if (port >= 0) {
            channel.write(new SetPortMessage(port));
        }

        for (MasterBoundMessage unhandledRequest : unhandledRequests) {
            channel.write(unhandledRequest);
        }

        lastPingReceived = System.currentTimeMillis();

        if (MinecraftServer.getServer() != null) {
            for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                level.chunkTaskScheduler.chunkHolderManager.getChunkHolders().forEach(chunkHolder -> {
                    chunkHolder.externalSubscribers.clear();
                    channel.write(new SubscribeChunkMessage(level.getWorld().getName(), chunkHolder.chunkX, chunkHolder.chunkZ));
                    if (chunkHolder.getEntityChunk() != null) {
                        chunkHolder.externalEntitiesSubscribers.clear();
                        channel.write(new SubscribeEntitiesMessage(level.getWorld().getName(), chunkHolder.chunkX, chunkHolder.chunkZ));
                    }
                    if (chunkHolder.hasExternalLockRequest) {
                        channel.write(new LockChunkMessage(chunkHolder.world.getWorld().getName(), chunkHolder.chunkX, chunkHolder.chunkZ));
                    }
                });
            }
        }

        channelActive = true;
        channel.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        channelActive = false;
        LOGGER.info("Lost connection to " + ((SocketChannel) ctx.channel()).remoteAddress());
        connect();
    }

    private void waitForActiveChannel() {
        while (channel == null || !channel.isActive() || !channelActive) {
            // Wait for channel to become active
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void ping() {
        if (!channelActive) return;

        if (System.currentTimeMillis() - lastPingReceived > PING_TIMEOUT) {
            LOGGER.error("Keep-alive ping timed out after {}ms, initiating master reconnect...", System.currentTimeMillis() - lastPingReceived);
            channelActive = false;
            channel.close();
            return;
        }

        send(new PingMessage(), message -> {
            lastPingReceived = System.currentTimeMillis();
        });
    }

    public void send(MasterBoundMessage message) {
        waitForActiveChannel();
        channel.writeAndFlush(message);
    }

    public void send(MasterBoundMessage message, Consumer<ServerBoundMessage> callback) {
        waitForActiveChannel();
        unhandledRequests.add(message);
        send(setCallback(message, reply -> {
            unhandledRequests.remove(message);
            callback.accept(reply);
        }));
    }

    public <T extends ServerBoundMessage> CompletableFuture<T> sendAndAwaitReply(MasterBoundMessage message, Class<T> expectedClass) {
        CompletableFuture<T> future = new CompletableFuture<>();

        send(message, reply -> {
            try {
                future.complete((T) reply);
            } catch (ClassCastException e) {
                e.printStackTrace();
            }
        });

        return future;
    }

    public Map<String, ExternalServer> getServersMap() {
        return serversMap;
    }

    public void setPort(int port) {
        if (!MultiPaperConfiguration.get().masterConnection.advertiseToBuiltInProxy) {
            return;
        }
        this.port = port;
        send(new SetPortMessage(port));
    }

    @Override
    public void handle(ServerInfoUpdateMessage message) {
        ExternalServer server = serversMap.computeIfAbsent(message.name, key -> new ExternalServer(key, key.equals(myName)));
        server.setAverageTickTime(message.averageTickTime);
        server.setTps(message.tps);
        server.setLastAlive(System.currentTimeMillis());
    }

    @Override
    public void handle(SetSecretMessage message) {
        secret = message.secret;
    }

    @Override
    public void handle(ServerStartedMessage message) {
        if (MinecraftServer.getServer() == null) {
            return;
        }

        LOGGER.info("Connecting to external server " + message.host + ":" + message.port + "...");

        ExternalServerConnection externalServerConnection = new ExternalServerConnection();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.handler(externalServerConnection);
        bootstrap.group(MessageBootstrap.getEventLoopGroup());

        if (MessageBootstrap.getEventLoopGroup() instanceof EpollEventLoopGroup) {
            bootstrap.channel(EpollSocketChannel.class);
        } else {
            bootstrap.channel(NioSocketChannel.class);
        }

        bootstrap.connect(message.host, message.port).addListener(future -> {
            if (future.isSuccess()) {
                externalServerConnection.sendMinecraftHandshake(message.host, secret, message.port);
            } else if (future.cause() != null) {
                future.cause().printStackTrace();
            }
        });
    }

    @Override
    public void handle(SetChunkOwnerMessage message) {
        ExternalServer server = message.owner.isEmpty() ? null : getOrCreateServer(message.owner);

        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
        if (newChunkHolder != null) {
            if (newChunkHolder.externalOwner != null && newChunkHolder.externalOwner.isMe() && server != null && !server.isMe()) {
                if (newChunkHolder.getCurrentChunk() instanceof LevelChunk levelChunk) {
                    server.getConnection().send(new SendEntitiesPacket(levelChunk));
                    server.getConnection().send(new SendTickListPacket(levelChunk));
                } else {
                    LOGGER.warn("Chunk " + message.cx + ", " + message.cz + " in world " + message.world + " is not a level chunk, but we own it and want to send tick list to " + server.getName());
                }
            }
            if (server != null && server.isMe()) {
                // Wait a bit for extra data to arrive before ticking the chunk
                newChunkHolder.externalOwner = null;
                MultiPaper.runSync(() -> newChunkHolder.externalOwner = server);
            } else if (server != null) {
                newChunkHolder.externalOwner = server;
                MultiPaper.runSync(() -> {
                    if (newChunkHolder.getCurrentChunk() instanceof LevelChunk levelChunk) {
                        levelChunk.blockTicks.removeIf(tick -> true);
                        levelChunk.fluidTicks.removeIf(tick -> true);
                    }
                });
            } else {
                newChunkHolder.externalOwner = null;
            }
            if (server != null && newChunkHolder.getCurrentChunk() != null && newChunkHolder.getCurrentChunk().getStatus() != ChunkStatus.FULL) {
                // A server has locked the chunk, which means their chunk must be full.
                // Let's redownload their full copy
                SendChunkPacket.forceChunkUnsafeUnload(message.world, message.cx, message.cz);
            }
        }

    }

    @Override
    public void handle(AddChunkSubscriberMessage message) {
        ExternalServer server = getOrCreateServer(message.server);

        MultiPaper.runSync(() -> {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (newChunkHolder != null) {
                newChunkHolder.externalSubscribers.add(server);
            } else {
                LOGGER.warn("Received AddChunkSubscriberMessage for unloaded chunk " + message.world + " " + message.cx + " " + message.cz);
            }
        });
    }

    @Override
    public void handle(RemoveChunkSubscriberMessage message) {
        ExternalServer server = getOrCreateServer(message.server);

        MultiPaper.runSync(() -> {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (newChunkHolder != null) {
                newChunkHolder.externalSubscribers.remove(server);
            } else {
                LOGGER.warn("Received RemoveChunkSubscriberMessage for unloaded chunk " + message.world + " " + message.cx + " " + message.cz);
            }
        });
    }

    @Override
    public void handle(ChunkSubscribersSyncMessage message) {
        ExternalServer ownerServer = message.owner.isEmpty() ? null : getOrCreateServer(message.owner);
        HashSet<ExternalServer> servers = new HashSet<>();
        for (String server : message.subscribers) {
            servers.add(getOrCreateServer(server));
        }

        MultiPaper.runSync(() -> {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (newChunkHolder != null) {
                newChunkHolder.externalOwner = ownerServer;
                newChunkHolder.externalSubscribers.clear();
                newChunkHolder.externalSubscribers.addAll(servers);
            } else {
                LOGGER.warn("Received ChunkSubscribersSyncMessage for unloaded chunk " + message.world + " " + message.cx + " " + message.cz);
            }
        });
    }

    @Override
    public void handle(ServerChangedChunkStatusMessage message) {
        ExternalServer server = getOrCreateServer(message.server);
        ChunkStatus status = BuiltInRegistries.CHUNK_STATUS.get(new ResourceLocation(message.status));

        if (!server.isMe()) {
            NewChunkHolder holder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (holder == null) {
                LOGGER.warn("Received a chunk change status notification for an unloaded chunk " + message.world + ";" + message.cx + ";" + message.cz + " from " + server.getName());
            } else if (holder.getCurrentChunk() != null && !holder.getCurrentChunk().getStatus().isOrAfter(status)) {
                SendChunkPacket.forceChunkUnsafeUnload(message.world, message.cx, message.cz);
            }
        }
    }

    @Override
    public void handle(DataUpdateMessage message) {
        String pathStr = message.path;
        File path = new File(pathStr);
        String file = path.getName().substring(0, path.getName().length() - 4); // Remove .dat suffix
        while (!path.getParentFile().equals(new File(".")) && !path.getParentFile().equals(path)) {
            path = path.getParentFile();
        }

        // Clear the data from the world's data cache
        final File finalPath = path;
        MultiPaper.runSync(() -> {
            World world = Bukkit.getWorld(finalPath.getName());
            if (world == null) {
                LOGGER.warn("Unknown world '" + finalPath.getName() + "' in path " + pathStr + " for clearData");
                return;
            }

            if (((CraftWorld) world).getHandle().getDataStorage().cache.remove(file) != null || dataCache.containsKey(file)) {
                dataCache.put(file, message.data);
            }
        });
    }

    @Override
    public void handle(AddEntitySubscriberMessage message) {
        ExternalServer server = getOrCreateServer(message.server);

        MultiPaper.runSync(() -> {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (newChunkHolder != null) {
                newChunkHolder.externalEntitiesSubscribers.add(server);
            } else {
                LOGGER.warn("Received an entities subscribe notification for an unloaded chunk " + message.world + ";" + message.cx + ";" + message.cz);
            }
        });

        MCUtil.scheduleTask(2, () -> {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (newChunkHolder != null) {
                ChunkEntitySlices chunkEntities = newChunkHolder.getEntityChunk();
                if (chunkEntities != null) {
                    // Send players' vehicles and passengers to the new subscriber
                    Set<Entity> rootVehiclesToSend = new HashSet<>();
                    for (Entity entity : chunkEntities.entities) {
                        Entity controllingPassenger = MultiPaperEntitiesHandler.getControllingPassenger(entity.getRootVehicle());
                        if (MultiPaper.isRealPlayer(controllingPassenger) && controllingPassenger != entity) {
                            rootVehiclesToSend.add(entity.getRootVehicle());
                        }
                    }
                    rootVehiclesToSend.forEach(entity -> EntityUpdateWithDependenciesPacket.sendVehicleAndPassengersPacketsRecursivelyToServers(entity, List.of(server)));
                }
            }
        });
    }

    @Override
    public void handle(RemoveEntitySubscriberMessage message) {
        ExternalServer server = getOrCreateServer(message.server);

        MultiPaper.runSync(() -> {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (newChunkHolder != null) {
                newChunkHolder.externalEntitiesSubscribers.remove(server);
            } else {
                LOGGER.warn("Received an entities unsubscribe notification for an unloaded chunk " + message.world + ";" + message.cx + ";" + message.cz);
            }
        });
    }

    @Override
    public void handle(EntitySubscribersSyncMessage message) {
        HashSet<ExternalServer> servers = new HashSet<>();
        for (String subscriber : message.subscribers) {
            servers.add(getOrCreateServer(subscriber));
        }

        MultiPaper.runSync(() -> {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(message.world, message.cx, message.cz);
            if (newChunkHolder != null) {
                newChunkHolder.externalEntitiesSubscribers.clear();
                newChunkHolder.externalEntitiesSubscribers.addAll(servers);
            } else {
                LOGGER.warn("Received an entities subscribe sync notification for an unloaded chunk " + message.world + ";" + message.cx + ";" + message.cz);
            }
        });
    }

    @Override
    public void handle(ShutdownMessage message) {
        Bukkit.shutdown();
    }

    @Override
    public void handle(FileContentMessage message) {
        File file = new File(message.path);
        File fileTemp;
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
            fileTemp = new File(file.getParentFile(), "." + file.getName() + "." + Double.toString(Math.random()).substring(2, 7) + ".tmp");
        } else {
            fileTemp = new File("." + file.getName() + "." + Double.toString(Math.random()).substring(2, 7) + ".tmp");
        }

        try {
            InboundDataStream dataStream = MultiPaper.getConnection().getDataStreamManager().createInboundDataStream(MultiPaper.getConnection().getChannel(), message.streamId);
            dataStream.copyToAsync(new FileOutputStream(fileTemp)).addListener(future2 -> {
                if (future2.cause() != null) {
                    future2.cause().printStackTrace();
                }

                fileTemp.setLastModified(message.lastModified);

                synchronized (MultiPaperFileSyncer.pathsBeingModified) {
                    MultiPaperFileSyncer.pathsBeingModified.add(message.path);
                }

                if (!fileTemp.renameTo(file) && fileTemp.isFile()) {
                    file.delete();
                    if (!fileTemp.renameTo(file)) {
                        LOGGER.warn("Failed to rename " + fileTemp.getPath() + " to " + file.getPath());
                    }
                }

                if (MultiPaperConfiguration.get().syncSettings.files.logFileSyncs) {
                    LOGGER.info("Downloaded synced file " + file.getPath() + " (" + (file.length() + 1023) / 1024 + "KB)");
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
