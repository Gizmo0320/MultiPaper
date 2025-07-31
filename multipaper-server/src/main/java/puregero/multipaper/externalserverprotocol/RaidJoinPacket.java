package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class RaidJoinPacket extends ExternalServerPacket {
    private static boolean handlingJoin = false;

    private final String world;
    private final UUID uuid;
    private final int raidId;

    public RaidJoinPacket(Entity entity, Raid raid) {
        this.world = entity.level().getWorld().getName();
        this.uuid = entity.getUUID();
        this.raidId = raid.getId();
    }

    public RaidJoinPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.raidId = in.readVarInt();
    }

    public static void broadcastJoin(Raider entity, Raid raid) {
        if (!handlingJoin) {
            MultiPaper.broadcastPacketToExternalServers(new RaidJoinPacket(entity, raid));
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeVarInt(raidId);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingJoin = true;
            World bukkitWorld = Bukkit.getWorld(world);

            if (bukkitWorld instanceof CraftWorld craftWorld) {
                ServerLevel level = craftWorld.getHandle();
                Entity entity = level.getEntityLookup().getEntityIgnoringAccessible(uuid);
                Raid raid = level.getRaids().get(raidId);
                if (raid != null && entity instanceof Raider raider) {
                    raid.joinRaid(raid.getGroupsSpawned(), raider, null, true);
                }
            }
            handlingJoin = false;
        });
    }
}
