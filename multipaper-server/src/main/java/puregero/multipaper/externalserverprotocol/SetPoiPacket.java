package puregero.multipaper.externalserverprotocol;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.Optional;
import java.util.UUID;

public class SetPoiPacket extends ExternalServerPacket {
    private static final Logger LOGGER = LogManager.getLogger(SetPoiPacket.class.getSimpleName());

    private static boolean handlingPacket = false;
    private final UUID world;
    private final BlockPos pos;
    private final Optional<ResourceKey<PoiType>> optionalKey;
    private final int freeTickets;

    public SetPoiPacket(ServerLevel level, BlockPos pos, Optional<Holder<PoiType>> holderOptional, int freeTickets) {
        this.world = level.getWorld().getUID();
        this.pos = pos;
        this.optionalKey = holderOptional.map(Holder::unwrapKey).flatMap(optional -> optional);
        this.freeTickets = freeTickets;
    }

    public static void broadcastUpdate(ServerLevel level, BlockPos pos) {
        if (!handlingPacket) {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(level, pos);
            if (newChunkHolder != null) {
                Optional<PoiSection> poiSectionOptional = Optional.ofNullable(level.getPoiManager().get(SectionPos.asLong(pos))).flatMap(optional -> optional);
                poiSectionOptional.ifPresent(poiSection -> {
                    poiSection.getPoiRecord(pos).ifPresentOrElse(poiRecord -> {
                        MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new SetPoiPacket(level, pos, Optional.of(poiRecord.getPoiType()), poiRecord.getFreeTickets()));
                    }, () -> {
                        MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new SetPoiPacket(level, pos, Optional.empty(), 0));
                    });
                });
            }
        }
    }

    public SetPoiPacket(FriendlyByteBuf in) {
        this.world = in.readUUID();
        this.pos = in.readBlockPos();
        this.optionalKey = in.readOptional(in2 -> in2.readResourceKey(BuiltInRegistries.POINT_OF_INTEREST_TYPE.key()));
        this.freeTickets = in.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(this.world);
        out.writeBlockPos(this.pos);
        out.writeOptional(this.optionalKey, FriendlyByteBuf::writeResourceKey);
        out.writeVarInt(this.freeTickets);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingPacket = true;
            World world = Bukkit.getWorld(this.world);
            if (world instanceof CraftWorld craftWorld) {
                PoiManager poiManager = craftWorld.getHandle().getPoiManager();
                this.optionalKey.ifPresentOrElse(key -> {
                    PoiSection poiSection = poiManager.getOrCreate(SectionPos.asLong(pos));
                    PoiRecord record = poiSection.getPoiRecord(pos).orElse(null);
                    if (record == null || !record.getPoiType().is(key)) {
                        poiManager.add(this.pos, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(key));
                        record = poiSection.getPoiRecord(pos).orElse(null);
                    }
                    if (record != null) {
                        record.setFreeTickets(freeTickets);
                    }
                }, () -> {
                    poiManager.remove(this.pos);
                });
            }
            handlingPacket = false;
        });
    }

    public static boolean shouldSavePoi() {
        return !handlingPacket;
    }
}
