package puregero.multipaper.externalserverprotocol;

import io.papermc.paper.world.ChunkEntitySlices;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ChunkKey;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.zip.InflaterInputStream;

public class SendEntitiesPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendEntitiesPacket.class.getSimpleName());

    private final String world;
    private final int cx;
    private final int cz;
    private final byte[] data;

    public SendEntitiesPacket(LevelChunk chunk) {
        this(chunk.level, chunk.getPos(), null);
    }

    public SendEntitiesPacket(LevelChunk chunk, ChunkEntitySlices chunkEntitySlices) {
        this(chunk.level, chunk.getPos(), chunkEntitySlices);
    }

    public SendEntitiesPacket(ServerLevel level, ChunkPos pos, ChunkEntitySlices chunkEntitySlices) {
        this(level.getWorld().getName(), pos.x, pos.z, getEntities(level, pos, null, chunkEntitySlices));
    }

    public static CompoundTag getEntities(ServerLevel level, ChunkPos pos, @Nullable Consumer<ServerPlayer> foreachPlayer) {
        return getEntities(level, pos, foreachPlayer, null);
    }

    public static CompoundTag getEntities(ServerLevel level, ChunkPos pos, @Nullable Consumer<ServerPlayer> foreachPlayer, @Nullable ChunkEntitySlices chunkEntities) {
        if (chunkEntities == null) {
            chunkEntities = level.getEntityLookup().getChunk(pos.x, pos.z);
            if (chunkEntities == null) {
                new Exception("Entities are not loaded in " + level.convertable.getLevelId() + pos + ", sending null entities").printStackTrace();
                return null;
            }
        }

        CompoundTag entitiesRoot = new CompoundTag();
        ListTag entities = new ListTag();
        for (Entity entity : chunkEntities.entities) {
            if (MultiPaperEntitiesHandler.shouldSyncEntity(entity)) {
                CompoundTag tag = new CompoundTag();
                entity.isSyncing = true;
                entity.save(tag);
                entity.isSyncing = false;
                entities.add(tag);
            } else if (foreachPlayer != null && entity instanceof ServerPlayer player && MultiPaper.isRealPlayer(player)) {
                foreachPlayer.accept(player);
            }
        }
        entitiesRoot.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        entitiesRoot.put("Entities", entities);
        entitiesRoot.put("Position", new IntArrayTag(new int[]{pos.x, pos.z}));
        return entitiesRoot;
    }

    public SendEntitiesPacket(String world, int cx, int cz, CompoundTag tag) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;

        try {
            if (tag == null) {
                data = new byte[0];
            } else {
                data = MultiPaper.nbtToBytes(tag);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SendEntitiesPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        cx = in.readInt();
        cz = in.readInt();
        data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeByteArray(data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        try {
            Consumer<DataInputStream> callback = connection.entitiesCallbacks.remove(new ChunkKey(world, cx, cz));
            if (callback != null) {
                if (data.length == 0) {
                    LOGGER.warn(connection.externalServer.getName() + " sent us an empty entities for " + world + "," + cx + "," + cz + ", force loading it from disk");
                    MultiPaper.forceReadChunk(world, "entities", cx, cz).thenAccept(data2 -> callback.accept(data2.length == 0 ? null : new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data2)))));
                } else {
                    callback.accept(new DataInputStream(new ByteArrayInputStream(data)));
                }
            } else {
                if (data.length == 0) {
                    return;
                }

                // Replace the existing entities with these new entities
                ChunkPos pos = new ChunkPos(cx, cz);
                ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
                // Check that we have these entities loaded in the first place
                if (level.getEntityLookup().isChunkLoaded(pos)) {
                    CompoundTag tag = MultiPaper.nbtFromBytes(data);
                    ListTag entities = tag == null ? new ListTag() : tag.getList("Entities", Tag.TAG_COMPOUND);
                    MultiPaper.runSync(() -> {
                        for (Tag entityTag : entities) {
                            CompoundTag entityTagCompound = (CompoundTag) entityTag;
                            EntityUpdateNBTPacket.loadEntity(level, entityTagCompound, entityTagCompound.getUUID("UUID"));
                        }
                    });
                }
                // commented out
                // set EntityUpdateNBTPacket line 109 for explanation
                // else {
                //  LOGGER.warn("Unsolicited entities for " + world + "," + cx + "," + cz);
                // }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
