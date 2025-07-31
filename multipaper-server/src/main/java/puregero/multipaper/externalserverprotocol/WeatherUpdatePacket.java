package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperRainHandler;

public class WeatherUpdatePacket extends ExternalServerPacket {
    private final String world;
    private final boolean raining;
    private final boolean thundering;
    private final int clearWeatherTime;
    private final int rainingTime;
    private final int thunderingTime;

    public WeatherUpdatePacket(String world, PrimaryLevelData levelData) {
        this.world = world;
        this.raining = levelData.isRaining();
        this.thundering = levelData.isThundering();
        this.clearWeatherTime = levelData.getClearWeatherTime();
        this.rainingTime = levelData.getRainTime();
        this.thunderingTime = levelData.getThunderTime();
    }

    public WeatherUpdatePacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.raining = in.readBoolean();
        this.thundering = in.readBoolean();
        this.clearWeatherTime = in.readInt();
        this.rainingTime = in.readInt();
        this.thunderingTime = in.readInt();
    }

    public void write(FriendlyByteBuf out) {
        out.writeUtf(this.world);
        out.writeBoolean(this.raining);
        out.writeBoolean(this.thundering);
        out.writeInt(this.clearWeatherTime);
        out.writeInt(this.rainingTime);
        out.writeInt(this.thunderingTime);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World bukkitWorld = Bukkit.getWorld(this.world);
            if (bukkitWorld != null) {
                ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
                MultiPaperRainHandler.handle(level, this.raining, this.thundering, this.clearWeatherTime, this.rainingTime, this.thunderingTime);
            }
        });
    }
}
