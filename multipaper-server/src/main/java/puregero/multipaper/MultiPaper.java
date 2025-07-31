package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import io.papermc.paper.world.ChunkEntitySlices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryCloseEvent;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.externalserverprotocol.*;
import puregero.multipaper.externalserverprotocol.ExternalServerPacket;
import puregero.multipaper.externalserverprotocol.PlayerCreatePacket;
import puregero.multipaper.externalserverprotocol.PlayerRemovePacket;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.*;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class MultiPaper {

    private static MultiPaperConnection multiPaperConnection = null;
    private static final Logger LOGGER = LogManager.getLogger(MultiPaper.class.getSimpleName());
    public static boolean levelDatNeedsSaving = false;
    private static long last1Seconds = System.currentTimeMillis();
    private static long last10Seconds = System.currentTimeMillis();

    public static MultiPaperConnection getConnection() {
        if (multiPaperConnection == null) {
            multiPaperConnection = new MultiPaperConnection();
        }

        return multiPaperConnection;
    }

    public static void tick() {
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().players) {
            if (player instanceof ExternalPlayer externalPlayer) {
                // Copied from Paper start - Configurable container update tick rate
                if (externalPlayer.containerMenu != externalPlayer.inventoryMenu && --externalPlayer.containerUpdateDelay <= 0) {
                    externalPlayer.containerMenu.broadcastChanges();
                    externalPlayer.containerUpdateDelay = externalPlayer.level().paperConfig().tickRates.containerUpdate;
                }
                // Copied from Paper end

                externalPlayer.checkInsideBlocks();

                externalPlayer.tickAttackStrength();

                if (player.takeXpDelay > 0) {
                    --player.takeXpDelay;
                }

                externalPlayer.tickDeathIfDead();

                if (externalPlayer.isSleeping()) {
                    externalPlayer.setSleepCounter(Math.min(100, externalPlayer.getSleepCounter() + 1));
                } else {
                    externalPlayer.setSleepCounter(0);
                }
            }

            player.syncExperience();

            player.connection.reduceSpamCounters();
        }

        for (ExternalServer server : getConnection().getServersMap().values()) {
            if (server.getConnection() != null) {
                // This tick function must be run after the vanilla tick
                server.getConnection().tick();
            }
        }

        if (levelDatNeedsSaving) {
            levelDatNeedsSaving = false;
            LOGGER.info("A level.dat needs saving, all worlds are being force saved");
            for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                level.saveIncrementally(true);
            }
        }
        
        boolean hasBeen1Seconds = last1Seconds < System.currentTimeMillis() - 1000;

        if (hasBeen1Seconds) last1Seconds = System.currentTimeMillis();

        Bukkit.getWorlds().forEach(world -> {
            DimensionDataStorage persistentData = ((CraftWorld) world).getHandle().getDataStorage();
            try {
                persistentData.cache.forEach((key, value) -> {
                    if (value instanceof MapIndex || (value instanceof MapItemSavedData && hasBeen1Seconds)) {
                        persistentData.save(key, value);
                    }
                });
            } catch (ConcurrentModificationException e) {
                // Ignore - plugins doing async things (HuskSync specifically)
            }
        });

        MultiPaperAckBlockChangesHandler.tick();

        MultiPaperInventoryHandler.tick();

        if (MinecraftServer.getServer().getTickCount() % 20 == 0) {
            for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                broadcastPacketToExternalServers(new TimeUpdatePacket(level));
            }
        }

        RequestEntityPacket.tick();

        MultiPaperExternalBlocksHandler.tick();

        boolean hasBeen10Seconds = last10Seconds < System.currentTimeMillis() - 10000;

        if (hasBeen10Seconds) {
            last10Seconds = System.currentTimeMillis();

            MultiPaperStatHandler.sendIncreases();
        }

        MultiPaperPermissionSyncer.sync();
    }

    public static void sendTickTime(long time, double tps) {
        getConnection().send(new WriteTickTimeMessage(time, (float) tps));
    }

    public static CompletableFuture<Boolean> sendPlayerConnect(ServerPlayer player) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        getConnection().send(new PlayerConnectMessage(player.getUUID()), message -> {
            future.complete(((BooleanMessageReply) message).result);
        });
        return future;
    }

    public static void sendPlayerDisconnect(ServerPlayer player) {
        getConnection().send(new PlayerDisconnectMessage(player.getUUID()));
    }

    public static void onStart(SocketAddress bindAddress) {
        getConnection().send(new StartMessage(
                System.getProperty("server.address", ((InetSocketAddress) bindAddress).getAddress().getHostAddress()),
                ((InetSocketAddress) bindAddress).getPort()
        ));
    }
    
    public static void runSync(Runnable runnable) {
        if (MinecraftServer.getServer() == null) {
            // Wait a bit for the server to start up
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> runSync(runnable));
            return;
        }

        MinecraftServer.getServer().scheduleOnMain(runnable);
    }

    public static void forEachExternalServer(Consumer<ExternalServer> externalServerConsumer) {
        getConnection().getServersMap().values().forEach(externalServerConsumer);
    }

    public static void broadcastPacketToExternalServers(ExternalServerPacket packet) {
        broadcastPacketToExternalServers(getConnection().getServersMap().values(), packet);
    }

    public static void broadcastPacketToExternalServers(Collection<ExternalServer> externalServers, ExternalServerPacket packet) {
        broadcastPacketToExternalServers(externalServers, () -> packet);
    }

    public static void broadcastPacketToExternalServers(Collection<ExternalServer> externalServers, Supplier<ExternalServerPacket> generatePacketIfNeeded) {
        if (!externalServers.isEmpty()) {
            ExternalServerPacket packet = generatePacketIfNeeded.get();
            externalServers.forEach(externalServer -> {
                if (!externalServer.isMe() && externalServer.getConnection() != null && externalServer.getConnection().isOpen()) {
                    externalServer.getConnection().send(packet);
                }
            });
        }
    }

    public static void broadcastPacketToExternalServers(String world, ExternalServerPacket packet) {
        forEachExternalServer(externalServer -> {
            if (externalServer.getConnection() != null && externalServer.getConnection().isOpen() && externalServer.getConnection().subscribedWorlds.contains(world)) {
                externalServer.getConnection().send(packet);
            }
        });
    }

    public static void broadcastPacketToExternalServers(ServerPlayer player, ExternalServerPacket packet) {
        if (player instanceof ExternalPlayer || player.didMultiPaperJoin) {
            broadcastPacketToExternalServers(player.level().getWorld().getName(), packet);
        }
    }

    public static void onPlayerJoin(ServerPlayer player) {
        player.didMultiPaperJoin = true;
        PlayerCreatePacket.sendPlayer(player,
                getConnection().getServersMap().values().stream()
                        .map(ExternalServer::getConnection)
                        .filter(connection -> connection != null && connection.isOpen() && connection.subscribedWorlds.contains(player.level().getWorld().getName()))
                        .toArray(ExternalServerConnection[]::new)
        );
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        if (!(player instanceof ExternalPlayer) && player.didMultiPaperJoin) {
            broadcastPacketToExternalServers(player, new PlayerRemovePacket(player));
        }
        if (MultiPaper.isRealPlayer(player)) {
            sendPlayerDisconnect(player);
        }
    }

    public static boolean isRealPlayer(Entity entity) {
        return entity instanceof ServerPlayer && !(entity instanceof ExternalPlayer);
    }

    public static boolean isRealPlayer(org.bukkit.entity.Entity bukkitEntity) {
        return isRealPlayer(((CraftEntity) bukkitEntity).getHandle());
    }

    public static boolean isExternalPlayer(Entity entity) {
        return entity instanceof ExternalPlayer;
    }

    public static boolean isExternalPlayer(org.bukkit.entity.Entity bukkitEntity) {
        return isExternalPlayer(((CraftEntity) bukkitEntity).getHandle());
    }

    public static byte[] nbtToBytes(CompoundTag compoundTag) throws IOException {
        if (compoundTag == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.write(compoundTag, new DataOutputStream(buffer));
        return buffer.toByteArray();
    }

    public static CompoundTag nbtFromBytes(byte[] data) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)));
    }

    public static CompletableFuture<Void> flushMasterConnectionAsync() {
        // Send and await a ping packet to ensure that all the queued packets in both directions have been fully flushed and handled
        return MultiPaper.getConnection().sendAndAwaitReply(new PingMessage(), BooleanMessageReply.class).thenRun(() -> {});
    }

    public static boolean isChunkExternal(Chunk chunk) {
        return chunk != null && ((CraftChunk) chunk).getHandle(ChunkStatus.EMPTY) instanceof LevelChunk levelChunk && isChunkExternal(levelChunk);
    }

    public static boolean isChunkExternal(LevelChunk chunk) {
        return chunk != null && isChunkExternal(chunk.getChunkHolder());
    }

    public static boolean isChunkExternal(ServerLevel level, BlockPos pos) {
        return isChunkExternal(level.chunkTaskScheduler.chunkHolderManager.getChunkHolder(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)));
    }

    public static boolean isChunkExternal(NewChunkHolder newChunkHolder) {
        return newChunkHolder != null && newChunkHolder.externalOwner != null && !newChunkHolder.externalOwner.isMe();
    }

    public static boolean isChunkLocal(Chunk chunk) {
        return chunk != null && ((CraftChunk) chunk).getHandle(ChunkStatus.EMPTY) instanceof LevelChunk levelChunk && isChunkLocal(levelChunk);
    }

    public static boolean isChunkLocal(LevelChunk chunk) {
        return chunk != null && isChunkLocal(chunk.getChunkHolder());
    }

    public static boolean isChunkLocal(ServerLevel level, BlockPos pos) {
        return isChunkLocal(level.chunkTaskScheduler.chunkHolderManager.getChunkHolder(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)));
    }

    public static boolean isChunkLocal(NewChunkHolder newChunkHolder) {
        return newChunkHolder != null && newChunkHolder.externalOwner != null && newChunkHolder.externalOwner.isMe();
    }

    public static CompoundTag readChunk(ChunkPos chunkPos, ServerLevel serverLevel) throws IOException {
        return readRegionFileNBT(serverLevel, "region", chunkPos);
    }

    public static void writeChunk(ChunkPos chunkPos, ServerLevel serverLevel, CompoundTag compoundTag) throws IOException {
        writeRegionFileNBT(serverLevel, "region", chunkPos, compoundTag);
    }

    public static CompletableFuture<byte[]> forceReadChunk(String world, String path, int cx, int cz) {
        return getConnection().sendAndAwaitReply(new ForceReadChunkMessage(world, path, cx, cz), DataMessageReply.class).thenApply(message -> message.data);
    }

    public static CompletableFuture<DataInputStream> readRegionFileAsync(String world, String path, int cx, int cz) {
        if (path.equals("region")) {
            World bukkitWorld = Bukkit.getWorld(world);
            if (bukkitWorld == null || MultiPaper.getChunkHolder(world, cx, cz) == null) {
                if (Bukkit.getPluginManager().getPlugin("Dynmap") == null) {
                    // Dynmap uses this, so don't log for Dynmap servers
                    LOGGER.warn(Thread.currentThread() + " has no chunk holder for reading chunk " + world + "," + path + "," + cx + "," + cz + ", reading it straight from disk instead");
                }

                return forceReadChunk(world, path, cx, cz).thenApply(data -> data.length == 0 ? null : new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))));
            }
        }

        return getConnection().sendAndAwaitReply(new ReadChunkMessage(world, path, cx, cz), ServerBoundMessage.class).thenCompose(message -> {
            if (message instanceof ChunkLoadedOnAnotherServerMessage chunkLoadedOnAnotherServerMessage) {
                ExternalServer server = getConnection().getServersMap().get(chunkLoadedOnAnotherServerMessage.server);
                CompletableFuture<DataInputStream> future = new CompletableFuture<>();
                if (server.getConnection() == null) {
                    // Don't throw the exception as that will cause the chunk to get corrupted and regenerate, losing data. Instead, allow the chunk loader to naturally timeout and try again.
                    new Exception("Tried to request a chunk " + world + "," + path + "," + cx + "," + cz + " from " + chunkLoadedOnAnotherServerMessage.server + ", but we are not connected to them!").printStackTrace();
                } else if (path.equals("region")) {
                    server.getConnection().requestChunk(world, cx, cz, inputStream -> {
                        RequestChunkPacket.blocker = null;
                        future.complete(inputStream);
                    });
                } else if (path.equals("entities")) {
                    server.getConnection().requestEntities(world, cx, cz, future::complete);
                } else {
                    throw new IllegalArgumentException("Cannot load a " + path + " chunk from an external server");
                }
                return future;
            } else if (message instanceof DataMessageReply dataMessageReply) {
                return CompletableFuture.completedFuture(dataMessageReply.data.length == 0 ? null : new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(dataMessageReply.data))));
            } else {
                throw new IllegalArgumentException("Unexpected message reply " + message);
            }
        });
    }

    public static DataInput readRegionFile(String world, String path, int cx, int cz) {
        try {
            return readRegionFileAsync(world, path, cx, cz).get(20, TimeUnit.SECONDS);
        } catch (TimeoutException timeoutException) {
            LOGGER.warn("Timed out reading " + world + "," + path + "," + cx + "," + cz + ", retrying...");
            return readRegionFile(world, path, cx, cz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeRegionFile(String world, String path, int cx, int cz, byte[] bytes) throws IOException {
        writeRegionFile(world, path, cx, cz, bytes, false);
    }

    public static void writeRegionFile(String world, String path, int cx, int cz, byte[] bytes, boolean isTransientEntities) throws IOException {
        if (bytes.length > 0) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DeflaterOutputStream deflateOut = new DeflaterOutputStream(baos);
            deflateOut.write(bytes);
            deflateOut.close();
            bytes = baos.toByteArray();
        }

        getConnection().send(new WriteChunkMessage(world, path, cx, cz, bytes, isTransientEntities), message -> { /* Do nothing */ });
    }
 
    public static CompoundTag readRegionFileNBT(File path, ChunkPos chunkPos) throws IOException {
        return readRegionFileNBT(getWorld(path), path.getName(), chunkPos.x, chunkPos.z);
    }

    public static CompoundTag readRegionFileNBT(ServerLevel serverLevel, String path, ChunkPos chunkPos) throws IOException {
        return readRegionFileNBT(serverLevel.getWorld().getName(), path, chunkPos.x, chunkPos.z);
    }

    public static CompoundTag readRegionFileNBT(String world, String path, int cx, int cz) throws IOException {
        DataInput in = readRegionFile(world, path, cx, cz);

        return in == null ? null : NbtIo.read(in);
    }
 
    public static void writeRegionFileNBT(File path, ChunkPos chunkPos, CompoundTag compoundTag) throws IOException {
        writeRegionFileNBT(getWorld(path), path.getName(), chunkPos.x, chunkPos.z, compoundTag);
    }

    public static void writeRegionFileNBT(ServerLevel serverLevel, String path, ChunkPos chunkPos, CompoundTag compoundTag) throws IOException {
        writeRegionFileNBT(serverLevel.getWorld().getName(), path, chunkPos.x, chunkPos.z, compoundTag);
    }

    public static void writeRegionFileNBT(String world, String path, int cx, int cz, CompoundTag compoundTag) throws IOException {
        writeRegionFile(world, path, cx, cz, nbtToBytes(compoundTag), compoundTag != null && compoundTag.contains("multipaper.transient"));
    }

    public static CompoundTag readLevel(String world) throws IOException {
        byte[] data = getConnection().sendAndAwaitReply(new ReadLevelMessage(world), DataMessageReply.class).thenApply(message -> message.data).join();

        return data.length == 0 ? null : NbtIo.readCompressed(new ByteArrayInputStream(data));
    }

    public static void writeLevel(String world, CompoundTag compoundTag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.writeCompressed(compoundTag, buffer);
        getConnection().send(new WriteLevelMessage(world, buffer.toByteArray()), message -> { /* do nothing */ });
    }

    public static String readJson(String name) throws IOException {
        if (MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            return getConnection().sendAndAwaitReply(new ReadJsonMessage(name), DataMessageReply.class).thenApply(message -> new String(message.data, StandardCharsets.UTF_8)).join();
        } else if (new File(name).isFile()) {
            return Files.readString(new File(name).toPath());
        } else {
            return null;
        }
    }

    public static void writeJson(String name, String json) throws IOException {
        if (MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            getConnection().send(new WriteJsonMessage(name, json.getBytes(StandardCharsets.UTF_8)), message -> { /* do nothing */ });
        } else {
            Files.writeString(new File(name).toPath(), json);
        }
    }

    public static CompoundTag readPlayer(String world, String uuid) throws IOException {
        byte[] data = getConnection().sendAndAwaitReply(new ReadPlayerMessage(world, uuid), DataMessageReply.class).thenApply(message -> message.data).join();

        return data.length == 0 ? null : NbtIo.readCompressed(new ByteArrayInputStream(data));
    }

    public static void writePlayer(String world, String uuid, CompoundTag compoundTag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.writeCompressed(compoundTag, buffer);
        getConnection().send(new WritePlayerMessage(world, uuid, buffer.toByteArray()), message -> { /* do nothing */ });
    }

    public static String readAdvancements(String world, String uuid) {
        return getConnection().sendAndAwaitReply(new ReadAdvancementMessage(world, uuid), DataMessageReply.class).thenApply(message -> new String(message.data, StandardCharsets.UTF_8)).join();
    }

    public static void writeAdvancements(String world, String uuid, String json) {
        getConnection().send(new WriteAdvancementsMessage(world, uuid, json.getBytes(StandardCharsets.UTF_8)), message -> { /* do nothing */ });
    }

    public static String readStats(String world, String uuid) {
        return getConnection().sendAndAwaitReply(new ReadStatsMessage(world, uuid), DataMessageReply.class).thenApply(message -> new String(message.data, StandardCharsets.UTF_8)).join();
    }

    public static void writeStats(String world, String uuid, String json) {
        getConnection().send(new WriteStatsMessage(world, uuid, json.getBytes(StandardCharsets.UTF_8)), message -> { /* do nothing */ });
    }

    public static DataInputStream readUid(String world) throws IOException {
        return getConnection().sendAndAwaitReply(new ReadUidMessage(world), DataMessageReply.class).thenApply(message -> new DataInputStream(new ByteArrayInputStream(message.data))).join();
    }

    public static void writeUid(String world, byte[] data) throws IOException {
        getConnection().send(new WriteUidMessage(world, data), message -> { /* do nothing */ });
    }

    public static byte[] readData(String path) {
        File pathFile = new File(path);
        String file = pathFile.getName().substring(0, pathFile.getName().length() - 4); // Remove .dat suffix
        if (getConnection().dataCache.containsKey(file)) {
            return getConnection().dataCache.remove(file);
        }

        return getConnection().sendAndAwaitReply(new ReadDataMessage(path), DataMessageReply.class).thenApply(message -> message.data).join();
    }

    public static void writeData(String path, CompoundTag compoundTag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.writeCompressed(compoundTag, buffer);
        getConnection().send(new WriteDataMessage(path, buffer.toByteArray()), message -> { /* do nothing */ });
    }

    private static String getWorld(File path) {
        do {
            path = path.getParentFile();
        } while (path.getName().startsWith("DIM"));
        return path.getName();
    }

    public static void lockChunk(NewChunkHolder newChunkHolder) {
        getConnection().send(new LockChunkMessage(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ));
        newChunkHolder.hasExternalLockRequest = true;
    }

    public static void unlockChunk(NewChunkHolder newChunkHolder, ChunkAccess chunkAccess, ChunkEntitySlices chunkEntitySlices) {
        if (chunkAccess instanceof LevelChunk levelChunk && MultiPaper.isChunkLocal(newChunkHolder)) {
            if (chunkEntitySlices != null) {
                chunkEntitySlices.entities.forEach(MultiPaperEntitiesHandler::onEntityUnlock);
            }
            broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new SendEntitiesPacket(levelChunk, chunkEntitySlices));
            broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new SendTickListPacket(levelChunk));
            levelChunk.level.getRaids().getActiveRaid(levelChunk.getPos()).ifPresent(RaidUpdatePacket::broadcastUpdate);
            for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
                if (blockEntity instanceof Container container) {
                    List<HumanEntity> viewers = container.getViewers();
                    if (!viewers.isEmpty()) {
                        for (HumanEntity viewer : new ArrayList<>(container.getViewers())) {
                            if (viewer instanceof CraftPlayer craftPlayer) {
                                craftPlayer.closeInventory(InventoryCloseEvent.Reason.UNLOADED);
                            }
                        }
                    }
                }
            }
        }
        getConnection().send(new UnlockChunkMessage(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ));
        newChunkHolder.externalOwner = null;
        newChunkHolder.hasExternalLockRequest = false;
    }

    public static void willSaveChunk(ServerLevel level, int x, int z) {
        getConnection().send(new WillSaveChunkLaterMessage(level.getWorld().getName(), x, z));
    }

    public static byte[] nbtCompressToBytes(CompoundTag compoundTag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(buffer);
        NbtIo.write(compoundTag, new DataOutputStream(deflaterOutputStream));
        deflaterOutputStream.close();
        return buffer.toByteArray();
    }

    public static CompoundTag nbtDecompressFromBytes(byte[] data) throws IOException {
        return NbtIo.read(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))));
    }

    public static ChunkAccess getChunkAccess(String world, int cx, int cz) {
        CraftWorld bukkitWorld = ((CraftWorld) Bukkit.getWorld(world));

        ChunkAccess chunkAccess = bukkitWorld != null ? bukkitWorld.getHandle().getChunkIfLoaded(cx, cz) : null;
        if (chunkAccess == null) {
            NewChunkHolder holder = getChunkHolder(world, cx, cz);
            if (holder != null) {
                chunkAccess = holder.getCurrentChunk();

                if (chunkAccess instanceof ImposterProtoChunk) {
                    chunkAccess = ((ImposterProtoChunk) chunkAccess).getWrapped();
                }
            }
        }

        return chunkAccess;
    }

    public static ChunkAccess getChunkAccess(String world, BlockPos pos) {
        return getChunkAccess(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static NewChunkHolder getChunkHolder(String world, BlockPos pos) {
        return getChunkHolder(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static NewChunkHolder getChunkHolder(UUID world, BlockPos pos) {
        return getChunkHolder(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static NewChunkHolder getChunkHolder(ServerLevel level, BlockPos pos) {
        return getChunkHolder(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static NewChunkHolder getChunkHolder(String world, int x, int z) {
        CraftWorld craftWorld = ((CraftWorld) Bukkit.getWorld(world));
        return craftWorld != null ? getChunkHolder(craftWorld.getHandle(), x, z) : null;
    }

    public static NewChunkHolder getChunkHolder(UUID world, int x, int z) {
        CraftWorld craftWorld = ((CraftWorld) Bukkit.getWorld(world));
        return craftWorld != null ? getChunkHolder(craftWorld.getHandle(), x, z) : null;
    }

    public static NewChunkHolder getChunkHolder(ServerLevel level, int x, int z) {
        return level.chunkTaskScheduler.chunkHolderManager.getChunkHolder(x, z);
    }

    public static NewChunkHolder getChunkHolder(Entity entity) {
        return getChunkHolder((ServerLevel) entity.level(), entity.chunkPosition().x, entity.chunkPosition().z);
    }

    public static void chunkChangedStatus(ServerLevel level, ChunkPos pos, ChunkStatus status) {
        getConnection().send(new ChunkChangedStatusMessage(level.getWorld().getName(), pos.x, pos.z, BuiltInRegistries.CHUNK_STATUS.getKey(status).toString()));
    }

    public static void setPort(int port) {
        getConnection().setPort(port);
    }
}
