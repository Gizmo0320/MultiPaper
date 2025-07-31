package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;

public class SendTickListPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendTickListPacket.class.getSimpleName());

    private final String world;
    private final int cx;
    private final int cz;
    private final CompoundTag tag;

    public SendTickListPacket(LevelChunk chunk) {
        this.world = chunk.level.convertable.getLevelId();
        this.cx = chunk.locX;
        this.cz = chunk.locZ;

        tag = new CompoundTag();
        tag.put("block_ticks", chunk.blockTicks.save(chunk.level.getLevelData().getGameTime(), (block) -> BuiltInRegistries.BLOCK.getKey(block).toString()));
        tag.put("fluid_ticks", chunk.fluidTicks.save(chunk.level.getLevelData().getGameTime(), (fluidtype) -> BuiltInRegistries.FLUID.getKey(fluidtype).toString()));
    }

    public SendTickListPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.cx = in.readInt();
        this.cz = in.readInt();

        byte[] data = in.readByteArray();
        try {
            tag = MultiPaper.nbtFromBytes(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);

        try {
            byte[] data = MultiPaper.nbtToBytes(tag);

            out.writeByteArray(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            CraftWorld bukkitWorld = ((CraftWorld) Bukkit.getWorld(world));
            ServerLevel level = bukkitWorld != null ? bukkitWorld.getHandle() : null;
            ChunkAccess chunk = MultiPaper.getChunkAccess(world, cx, cz);
            if (level != null && level.getChunkIfLoaded(cx, cz) != null) {
                long now = level.getLevelData().getGameTime();

                LevelChunkTicks<Block> blockTicks = LevelChunkTicks.load(tag.getList("block_ticks", Tag.TAG_COMPOUND), s -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(s)), chunk.getPos());
                blockTicks.unpack(now);
                blockTicks.removeIf(scheduled -> {
                    level.getBlockTicks().schedule(scheduled);
                    return true;
                });

                LevelChunkTicks<Fluid> fluidTicks = LevelChunkTicks.load(tag.getList("fluid_ticks", Tag.TAG_COMPOUND), s -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(s)), chunk.getPos());
                fluidTicks.unpack(now);
                fluidTicks.removeIf(scheduled -> {
                    level.getFluidTicks().schedule(scheduled);
                    return true;
                });
            } else if (chunk instanceof LevelChunk levelChunk) {
                levelChunk.unregisterTickContainerFromLevel(level);
                levelChunk.blockTicks = LevelChunkTicks.load(tag.getList("block_ticks", Tag.TAG_COMPOUND), s -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(s)), chunk.getPos());
                levelChunk.fluidTicks = LevelChunkTicks.load(tag.getList("fluid_ticks", Tag.TAG_COMPOUND), s -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(s)), chunk.getPos());
                levelChunk.unpackTicks(level.getLevelData().getGameTime());
                if (levelChunk.loaded) levelChunk.registerTickContainerInLevel(level);
            } else if (chunk instanceof ProtoChunk protoChunk) {
                protoChunk.blockTicks = ProtoChunkTicks.load(tag.getList("block_ticks", Tag.TAG_COMPOUND), s -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(s)), chunk.getPos());
                protoChunk.fluidTicks = ProtoChunkTicks.load(tag.getList("fluid_ticks", Tag.TAG_COMPOUND), s -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(s)), chunk.getPos());
            } else {
                LOGGER.warn("Received tick lists for an unloaded chunk " + world + "," + cx + "," + cz);
            }
        });
    }
}
