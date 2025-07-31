package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.entity.player.Player;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperStatHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerStatsIncreasePacket extends ExternalServerPacket {
    private final UUID uuid;
    private final HashMap<Stat<?>, Integer> stats;
    private ByteBuf cache;

    public PlayerStatsIncreasePacket(Player player, HashMap<Stat<?>, Integer> stats) {
        this.uuid = player.getUUID();
        this.stats = stats;

        // Write the packet now so that we can pool the stats hashmap
        ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.buffer();
        FriendlyByteBuf out = new FriendlyByteBuf(buffer);
        write(out);
        this.cache = buffer;
    }

    public PlayerStatsIncreasePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();

        int length = in.readInt();
        this.stats = MultiPaperStatHandler.hashMapPool.acquire();
        for (int i = 0; i < length; i++) {
            String type = in.readUtf();
            String subtype = in.readUtf();

            StatType<?> statType = BuiltInRegistries.STAT_TYPE.get(new ResourceLocation(type));
            Stat<?> stat = getStat(statType, subtype);

            stats.put(stat, in.readInt());
        }
    }

    private static <T> Stat<T> getStat(StatType<T> type, String subtype) {
        ResourceLocation subtypeLocation = new ResourceLocation(subtype);
        Registry<T> typeRegistry = type.getRegistry();
        return type.get(typeRegistry.get(subtypeLocation));
    }

    private static <T> ResourceLocation getStatLocation(Stat<T> stat) {
        Registry<T> typeRegistry = stat.getType().getRegistry();
        return typeRegistry.getKey(stat.getValue());
    }

    @Override
    public synchronized void write(FriendlyByteBuf out) {
        if (cache != null) {
            cache.readerIndex(0);
            out.writeBytes(cache);
            return;
        }

        Set<Map.Entry<Stat<?>, Integer>> entries = stats.entrySet();

        out.writeUUID(uuid);
        out.writeInt(entries.size());

        for (Map.Entry<Stat<?>, Integer> entry : entries) {
            out.writeUtf(BuiltInRegistries.STAT_TYPE.getKey(entry.getKey().getType()).toString());
            out.writeUtf(getStatLocation(entry.getKey()).toString());
            out.writeInt(entry.getValue());
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player != null) {
                stats.forEach((stat, value) -> {
                    int newValue = (int) Math.min((long) player.getStats().getValue(stat) + (long) value, 2147483647L);
                    player.getStats().setValue(player, stat, newValue);
                });
            }

            stats.clear();
            MultiPaperStatHandler.hashMapPool.release(stats);
        });
    }

}
