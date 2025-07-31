package puregero.multipaper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.PrimaryLevelData;
import puregero.multipaper.externalserverprotocol.WeatherUpdatePacket;

public class MultiPaperRainHandler {
    private static boolean updatingWeather = false;

    public static void onWeatherChange(PrimaryLevelData levelData, String world) {
        if (!updatingWeather) {
            // Run after all rain parameters have been set
            MultiPaper.runSync(() -> MultiPaper.broadcastPacketToExternalServers(new WeatherUpdatePacket(world, levelData)));
        }
    }

    public static void handle(ServerLevel level, boolean raining, boolean thundering, int clearWeatherTime, int rainingTime, int thunderingTime) {
        updatingWeather = true;
        PrimaryLevelData levelData = (PrimaryLevelData) level.getLevelData();
        levelData.setRaining(raining);
        levelData.setThundering(thundering);
        levelData.setClearWeatherTime(clearWeatherTime);
        levelData.setRainTime(rainingTime);
        levelData.setThunderTime(thunderingTime);
        updatingWeather = false;
    }
}
