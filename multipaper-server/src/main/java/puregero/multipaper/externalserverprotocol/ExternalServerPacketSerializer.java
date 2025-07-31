package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class ExternalServerPacketSerializer {

    private static final List<Class<? extends ExternalServerPacket>> PACKETS = new ArrayList<>();
    private static final List<Function<FriendlyByteBuf, ExternalServerPacket>> PACKET_DESERIALIZERS = new ArrayList<>();
    static {
        addPacket(HelloPacket.class, HelloPacket::new);
        addPacket(SetCompressionPacket.class, SetCompressionPacket::new);
        addPacket(SubscribeToWorldPacket.class, SubscribeToWorldPacket::new);
        addPacket(PlayerCreatePacket.class, PlayerCreatePacket::new);
        addPacket(PlayerRemovePacket.class, PlayerRemovePacket::new);
        addPacket(PlayerChangeGamemodePacket.class, PlayerChangeGamemodePacket::new);
        addPacket(PlayerRespawnPacket.class, PlayerRespawnPacket::new);
        addPacket(PlayerChangeDimensionPacket.class, PlayerChangeDimensionPacket::new);
        addPacket(SendPacketPacket.class, SendPacketPacket::new);
        addPacket(SendUpdatePacket.class, SendUpdatePacket::new);
        addPacket(RequestChunkPacket.class, RequestChunkPacket::new);
        addPacket(SendChunkPacket.class, SendChunkPacket::new);
        addPacket(SendTickListPacket.class, SendTickListPacket::new);
        addPacket(BanPlayerPacket.class, BanPlayerPacket::new);
        addPacket(PardonPlayerPacket.class, PardonPlayerPacket::new);
        addPacket(BanIpPacket.class, BanIpPacket::new);
        addPacket(PardonIpPacket.class, PardonIpPacket::new);
        addPacket(OpPlayerPacket.class, OpPlayerPacket::new);
        addPacket(DeopPlayerPacket.class, DeopPlayerPacket::new);
        addPacket(WhiteListPlayerPacket.class, WhiteListPlayerPacket::new);
        addPacket(RemoveWhiteListedPlayerPacket.class, RemoveWhiteListedPlayerPacket::new);
        addPacket(PlayerActionPacket.class, PlayerActionPacket::new);
        addPacket(PlayerInventoryUpdatePacket.class, PlayerInventoryUpdatePacket::new);
        addPacket(TimeUpdatePacket.class, TimeUpdatePacket::new);
        addPacket(RequestEntitiesPacket.class, RequestEntitiesPacket::new);
        addPacket(SendEntitiesPacket.class, SendEntitiesPacket::new);
        addPacket(EntityUpdateNBTPacket.class, EntityUpdateNBTPacket::new);
        addPacket(EntityUpdatePacket.class, EntityUpdatePacket::new);
        addPacket(EntityUpdateWithDependenciesPacket.class, EntityUpdateWithDependenciesPacket::new);
        addPacket(RequestEntityPacket.class, RequestEntityPacket::new);
        addPacket(EntityRemovePacket.class, EntityRemovePacket::new);
        addPacket(PlayerActionOnEntityPacket.class, PlayerActionOnEntityPacket::new);
        addPacket(PlayerTouchEntityPacket.class, PlayerTouchEntityPacket::new);
        addPacket(SetEndDragonFightDataPacket.class, SetEndDragonFightDataPacket::new);
        addPacket(HurtEntityPacket.class, HurtEntityPacket::new);
        addPacket(PlayerResetAttackStrengthPacket.class, PlayerResetAttackStrengthPacket::new);
        addPacket(AddItemToEntityContainerPacket.class, AddItemToEntityContainerPacket::new);
        addPacket(AddItemToContainerPacket.class, AddItemToContainerPacket::new);
        addPacket(PullItemFromContainerPacket.class, PullItemFromContainerPacket::new);
        addPacket(MobSetNavigationGoalPacket.class, MobSetNavigationGoalPacket::new);
        addPacket(PlayerDataUpdatePacket.class, PlayerDataUpdatePacket::new);
        addPacket(PluginNotificationPacket.class, PluginNotificationPacket::new);
        addPacket(PlayerSayChatPacket.class, PlayerSayChatPacket::new);
        addPacket(PlayerFoodUpdatePacket.class, PlayerFoodUpdatePacket::new);
        addPacket(EntityUpdateEffectPacket.class, EntityUpdateEffectPacket::new);
        addPacket(GameRuleUpdatePacket.class, GameRuleUpdatePacket::new);
        addPacket(AdvancementGrantProgressPacket.class, AdvancementGrantProgressPacket::new);
        addPacket(WeatherUpdatePacket.class, WeatherUpdatePacket::new);
        addPacket(PlayerStatsIncreasePacket.class, PlayerStatsIncreasePacket::new);
        addPacket(PlayerExperienceUpdatePacket.class, PlayerExperienceUpdatePacket::new);
        addPacket(PlayerListNameUpdatePacket.class, PlayerListNameUpdatePacket::new);
        addPacket(PlayerSetCameraPacket.class, PlayerSetCameraPacket::new);
        addPacket(PlayerSetRespawnPosition.class, PlayerSetRespawnPosition::new);
        addPacket(SpawnUpdatePacket.class, SpawnUpdatePacket::new);
        addPacket(DifficultyUpdatePacket.class, DifficultyUpdatePacket::new);
        addPacket(ScoreboardUpdatePacket.class, ScoreboardUpdatePacket::new);
        addPacket(WhitelistTogglePacket.class, WhitelistTogglePacket::new);
        addPacket(DestroyBlockPacket.class, DestroyBlockPacket::new);
        addPacket(DestroyAndAckPacket.class, DestroyAndAckPacket::new);
        addPacket(EntityTeleportPacket.class, EntityTeleportPacket::new);
        addPacket(ProjectileHitEntityPacket.class, ProjectileHitEntityPacket::new);
        addPacket(PlayerUseBlockPacket.class, PlayerUseBlockPacket::new);
        addPacket(RaidUpdatePacket.class, RaidUpdatePacket::new);
        addPacket(RaidJoinPacket.class, RaidJoinPacket::new);
        addPacket(SetPoiPacket.class, SetPoiPacket::new);
        addPacket(AddDeltaMovementPacket.class, AddDeltaMovementPacket::new);
        addPacket(PistonMoveBlockStartPacket.class, PistonMoveBlockStartPacket::new);
        addPacket(PistonMoveBlockEndPacket.class, PistonMoveBlockEndPacket::new);
        addPacket(AddPendingMessagePacket.class, AddPendingMessagePacket::new);
        addPacket(SetPlayerChatStatePacket.class, SetPlayerChatStatePacket::new);
        addPacket(EntityPersistentDataUpdatePacket.class, EntityPersistentDataUpdatePacket::new);
    }

    private static void addPacket(Class<? extends ExternalServerPacket> clazz, Function<FriendlyByteBuf, ExternalServerPacket> deserializer) {
        PACKETS.add(clazz);
        PACKET_DESERIALIZERS.add(deserializer);
    }

    public static int getPacketId(ExternalServerPacket packet) {
        int id = PACKETS.indexOf(packet.getClass());
        if (id == -1) {
            System.err.println("Unknown packet " + packet);
            throw new IllegalArgumentException("Unknown packet " + packet);
        }
        return id;
    }

    public static Function<FriendlyByteBuf, ExternalServerPacket> getDeserializer(int packetId) {
        return PACKET_DESERIALIZERS.get(packetId);
    }
}
