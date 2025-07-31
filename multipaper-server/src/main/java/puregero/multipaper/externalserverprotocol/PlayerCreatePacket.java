package puregero.multipaper.externalserverprotocol;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.GameType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerKickEvent;
import puregero.multipaper.*;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.event.player.PlayerJoinExternalServerEvent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerCreatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerCreatePacket.class.getSimpleName());

    private final GameProfile gameProfile;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final GameType gamemode;
    private final byte[] ip;
    private final short port;
    private final CompoundTag saveData;
    private final String advancements;
    private final String stats;
    private final ConcurrentHashMap<String, String> data;
    private final ConcurrentHashMap<String, String> persistentData;
    private final int entityId;

    private PlayerCreatePacket(ServerPlayer player, CompoundTag saveData) {
        this.gameProfile = player.gameProfile;
        this.world = player.level().getWorld().getName();
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.yaw = player.getYRot();
        this.pitch = player.getXRot();
        this.gamemode = player.gameMode.getGameModeForPlayer();
        this.ip = ((InetSocketAddress) player.connection.connection.address).getAddress().getAddress();
        this.port = (short) ((InetSocketAddress) player.connection.connection.address).getPort();
        this.saveData = saveData;
        this.advancements = player.getAdvancements().generateJson().toString();
        this.stats = player.getStats().toJson();
        this.data = player.getBukkitEntity().data;
        this.persistentData = player.getBukkitEntity().persistentData;
        this.entityId = player.getId();
    }

    public PlayerCreatePacket(FriendlyByteBuf in) {
        in.maxNbtSize = Long.MAX_VALUE; // Allow unlimited NBT size
        gameProfile = in.readGameProfile();
        world = in.readUtf();
        x = in.readDouble();
        y = in.readDouble();
        z = in.readDouble();
        yaw = in.readFloat();
        pitch = in.readFloat();
        gamemode = GameType.byId(in.readByte());
        ip = in.readByteArray();
        port = in.readShort();
        saveData = in.readNbt();

        advancements = in.readUtf(Integer.MAX_VALUE / 6); // divide by 6 cause mojang's code doesn't allow for a full 2^31-1 max length
        stats = in.readUtf(Integer.MAX_VALUE / 6);

        data = new ConcurrentHashMap<>();
        int dataLength = in.readInt();
        for (int i = 0; i < dataLength; i++) {
            data.put(in.readUtf(), in.readUtf());
        }

        persistentData = new ConcurrentHashMap<>();
        int persistentDataLength = in.readInt();
        for (int i = 0; i < persistentDataLength; i++) {
            persistentData.put(in.readUtf(), in.readUtf());
        }

        entityId = in.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeGameProfile(gameProfile);
        out.writeUtf(world);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
        out.writeByte(gamemode.getId());
        out.writeByteArray(ip);
        out.writeShort(port);
        out.writeNbt(saveData);

        out.writeUtf(advancements, Integer.MAX_VALUE / 6); // divide by 6 cause mojang's code doesn't allow for a full 2^31-1 max length
        out.writeUtf(stats, Integer.MAX_VALUE / 6);

        Collection<Map.Entry<String, String>> dataEntries = new ArrayList<>(data.entrySet());
        out.writeInt(dataEntries.size());
        for (Map.Entry<String, String> entry : dataEntries) {
            out.writeUtf(entry.getKey());
            out.writeUtf(entry.getValue());
        }

        Collection<Map.Entry<String, String>> persistentDataEntries = new ArrayList<>(persistentData.entrySet());
        out.writeInt(persistentDataEntries.size());
        for (Map.Entry<String, String> entry : persistentDataEntries) {
            out.writeUtf(entry.getKey());
            out.writeUtf(entry.getValue());
        }

        out.writeVarInt(entityId);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        LOGGER.info("Adding player " + gameProfile.getName() + " (" + gameProfile.getId() + ")");
        MultiPaper.runSync(() -> {
            ServerPlayer existingPlayer = MinecraftServer.getServer().getPlayerList().getPlayer(gameProfile.getId());
            if (existingPlayer != null) {
                LOGGER.warn("Trying to add external player " + gameProfile.getName() + " (" + gameProfile.getId() + "), but they're already online as a " + existingPlayer.getClass().getSimpleName() + ", kicking them");
                existingPlayer.connection.disconnect(PlayerRemovePacket.LOGGED_IN_FROM_ANOTHER_LOCATION, PlayerKickEvent.Cause.DUPLICATE_LOGIN);
            }

            InetSocketAddress address = null;
            try {
                address = new InetSocketAddress(InetAddress.getByAddress(ip), port & 0xFFFF);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }

            ExternalPlayer player = ExternalPlayer.create(connection, gameProfile, world, x, y, z, yaw, pitch, gamemode, address, saveData, advancements, stats, entityId);
            player.getBukkitEntity().data = data;
            player.getBukkitEntity().persistentData = persistentData;
            PlayerJoinExternalServerEvent playerJoinExternalServerEvent = new PlayerJoinExternalServerEvent(gameProfile.getId(), gameProfile.getName(), MultiPaperConfiguration.get().masterConnection.myName);
            Bukkit.getPluginManager().callEvent(playerJoinExternalServerEvent);
        });
    }

    private static void send(ExternalServerPacket packet, ExternalServerConnection... connections) {
        for (ExternalServerConnection connection : connections) {
            connection.send(packet);
        }
    }

    public static void sendPlayer(ServerPlayer player, ExternalServerConnection... connections) {
        if (connections.length == 0) {
            // Don't process packets if there's no one to send to
            return;
        }

        send(new PlayerCreatePacket(player, player.saveWithoutId(new CompoundTag())), connections);
        send(new PlayerActionPacket(player, new ServerboundSetCarriedItemPacket(player.getInventory().selected)), connections);
        send(new EntityUpdatePacket(player, new ClientboundSetEntityDataPacket(player.getId(), player.getEntityData().getAll())), connections);
        send(new PlayerFoodUpdatePacket(player), connections);
        send(new PlayerListNameUpdatePacket(player), connections);
        send(new PlayerSetRespawnPosition(player), connections);

        send(new EntityUpdatePacket(player, new ClientboundSetEntityDataPacket(player.getId(), player.getEntityData().getAll())), connections);

        if (player.clientViewDistance != null) {
            send(new PlayerActionPacket(player, new ServerboundClientInformationPacket(
                    player.locale,
                    player.clientViewDistance,
                    player.getChatVisibility(),
                    player.canChatInColor(),
                    player.getEntityData().get(ServerPlayer.DATA_PLAYER_MODE_CUSTOMISATION),
                    player.getEntityData().get(ServerPlayer.DATA_PLAYER_MAIN_HAND) == 0 ? HumanoidArm.LEFT : HumanoidArm.RIGHT,
                    player.isTextFilteringEnabled(),
                    player.allowsListing()
            )), connections);
        }

        if (player.isPassenger() || player.isVehicle()) {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(player.getRootVehicle());
            if (newChunkHolder != null) {
                List<ExternalServer> subscribedServers = Arrays.stream(connections).filter(e -> newChunkHolder.externalEntitiesSubscribers.contains(e.externalServer)).map(connection -> connection.externalServer).toList();
                EntityUpdateWithDependenciesPacket.sendVehicleAndPassengersPacketsRecursivelyToServers(player.getRootVehicle(), subscribedServers);
            }
        }
    }
}
