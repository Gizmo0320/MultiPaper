package puregero.multipaper;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.NotNull;
import puregero.multipaper.externalserverprotocol.SendUpdatePacket;

import java.util.UUID;

public class MultiPaperWorldBorderHandler implements BorderChangeListener {

    public static boolean updatingWorldBorder = false;

    @Override
    public void onBorderSizeSet(@NotNull WorldBorder border, double size) {
        onWorldBorderChange(border, new ClientboundSetBorderSizePacket(border));
    }

    @Override
    public void onBorderSizeLerping(@NotNull WorldBorder border, double fromSize, double toSize, long time) {
        onWorldBorderChange(border, new ClientboundSetBorderLerpSizePacket(border));
    }

    @Override
    public void onBorderCenterSet(@NotNull WorldBorder border, double centerX, double centerZ) {
        onWorldBorderChange(border, new ClientboundSetBorderCenterPacket(border));
    }

    @Override
    public void onBorderSetWarningTime(@NotNull WorldBorder border, int warningTime) {
        onWorldBorderChange(border, new ClientboundSetBorderWarningDelayPacket(border));
    }

    @Override
    public void onBorderSetWarningBlocks(@NotNull WorldBorder border, int warningBlockDistance) {
        onWorldBorderChange(border, new ClientboundSetBorderWarningDistancePacket(border));
    }

    @Override
    public void onBorderSetDamagePerBlock(@NotNull WorldBorder border, double damagePerBlock) {}

    @Override
    public void onBorderSetDamageSafeZOne(@NotNull WorldBorder border, double safeZoneRadius) {}

    private void onWorldBorderChange(WorldBorder border, Packet<?> packet) {
        if (!updatingWorldBorder && border.world != null && border.world.getWorldBorder() == border) {
            MultiPaper.broadcastPacketToExternalServers(border.world.getWorld().getName(), new SendUpdatePacket(border.world.uuid, packet));

            // Save the level.dat
            border.world.saveIncrementally(true);
        }
    }

    public static void handle(UUID world, Packet<?> packet) {
        ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();

        updatingWorldBorder = true;

        if (packet instanceof ClientboundSetBorderSizePacket setBorderSizePacket) {
            level.getWorldBorder().setSize(setBorderSizePacket.getSize());
        } else if (packet instanceof ClientboundSetBorderLerpSizePacket setBorderLerpSizePacket) {
            level.getWorldBorder().lerpSizeBetween(setBorderLerpSizePacket.getOldSize(), setBorderLerpSizePacket.getNewSize(), setBorderLerpSizePacket.getLerpTime());
        } else if (packet instanceof ClientboundSetBorderCenterPacket setBorderCenterPacket) {
            level.getWorldBorder().setCenter(setBorderCenterPacket.getNewCenterX(), setBorderCenterPacket.getNewCenterZ());
        } else if (packet instanceof ClientboundSetBorderWarningDelayPacket setBorderWarningDelayPacket) {
            level.getWorldBorder().setWarningTime(setBorderWarningDelayPacket.getWarningDelay());
        } else if (packet instanceof ClientboundSetBorderWarningDistancePacket setBorderWarningDistancePacket) {
            level.getWorldBorder().setWarningBlocks(setBorderWarningDistancePacket.getWarningBlocks());
        }

        updatingWorldBorder = false;
    }
}
