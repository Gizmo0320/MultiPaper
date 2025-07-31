package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class SetEndDragonFightDataPacket extends ExternalServerPacket {
    private static final Logger LOGGER = LogManager.getLogger(SetEndDragonFightDataPacket.class.getSimpleName());

    private final UUID world;
    private final EndDragonFight.Data data;

    public SetEndDragonFightDataPacket(ServerLevel level, EndDragonFight.Data data) {
        this.world = level.getWorld().getUID();
        this.data = data;
    }

    public SetEndDragonFightDataPacket(FriendlyByteBuf in) {
        this.world = in.readUUID();
        this.data = in.readJsonWithCodec(EndDragonFight.Data.CODEC);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(this.world);
        out.writeJsonWithCodec(EndDragonFight.Data.CODEC, this.data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World world = Bukkit.getWorld(this.world);
            if (world instanceof CraftWorld craftWorld) {
                EndDragonFight endDragonFight = craftWorld.getHandle().getDragonFight();
                if (endDragonFight != null) {
                    endDragonFight.load(this.data);
                }
            }
        });
    }
}
