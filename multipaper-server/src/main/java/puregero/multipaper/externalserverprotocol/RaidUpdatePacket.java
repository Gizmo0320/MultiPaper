package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;

public class RaidUpdatePacket extends ExternalServerPacket {
    private final String world;
    private final byte[] data;

    public RaidUpdatePacket(Raid raid) {
        this.world = raid.getLevel().getWorld().getName();

        CompoundTag tag = new CompoundTag();

        raid.save(tag);

        try {
            this.data = MultiPaper.nbtToBytes(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public RaidUpdatePacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeByteArray(data);
    }

    public static void broadcastUpdate(Raid raid) {
        MultiPaper.broadcastPacketToExternalServers(new RaidUpdatePacket(raid));
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            try {
                World bukkitWorld = Bukkit.getWorld(world);

                if (bukkitWorld instanceof CraftWorld craftWorld) {
                    ServerLevel level = craftWorld.getHandle();
                    CompoundTag tag = MultiPaper.nbtFromBytes(data);
                    int id = tag.getInt("Id");

                    Raid raid = level.getRaids().raidMap.get(id);
                    if (raid == null) {
                        raid = new Raid(level, tag);
                    } else {
                        raid.load(level, tag);
                    }

                    level.getRaids().raidMap.put(raid.getId(), raid);

                    if (!raid.isStopped()) {
                        level.getRaids().chunkToRaidIdMap.put(ChunkPos.asLong(raid.getCenter()), raid.getId());
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
