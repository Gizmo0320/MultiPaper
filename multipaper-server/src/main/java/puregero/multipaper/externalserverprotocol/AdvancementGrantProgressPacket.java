package puregero.multipaper.externalserverprotocol;

import net.minecraft.advancements.Advancement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class AdvancementGrantProgressPacket extends ExternalServerPacket {

    private static boolean updatingAdvancements = false;

    private final UUID uuid;
    private final String advancement;
    private final String criterion;

    public AdvancementGrantProgressPacket(ServerPlayer player, Advancement advancement, String criterion) {
        this.uuid = player.getUUID();
        this.advancement = advancement.getId().toString();
        this.criterion = criterion;
    }

    public AdvancementGrantProgressPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        advancement = in.readUtf();
        criterion = in.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUtf(advancement);
        out.writeUtf(criterion);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            updatingAdvancements = true;
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.getAdvancements().award(player.getServer().getAdvancements().getAdvancement(new ResourceLocation(advancement)), criterion);
            }
            updatingAdvancements = false;
        });
    }

    public static void onAdvancementGrantProgress(ServerPlayer player, Advancement advancement, String criterion) {
        if (!updatingAdvancements) {
            MultiPaper.broadcastPacketToExternalServers(new AdvancementGrantProgressPacket(player, advancement, criterion));
        }
    }
}
