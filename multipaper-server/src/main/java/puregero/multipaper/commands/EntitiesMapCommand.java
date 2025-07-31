package puregero.multipaper.commands;

import io.papermc.paper.world.ChunkEntitySlices;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public class EntitiesMapCommand extends MapCommandBase {

    private static final ChunkStatus UNLOADED = new ChunkStatus(NamedTextColor.DARK_GRAY, "Unloaded");
    private static final ChunkStatus TRANSIENT = new ChunkStatus(NamedTextColor.RED, "Transient (Not loaded, but has entities)");
    private static final ChunkStatus EMPTY = new ChunkStatus(NamedTextColor.WHITE, "Empty (Loaded, but has no entities)");
    private static final ChunkStatus LOADED = new ChunkStatus(NamedTextColor.GREEN, "Loaded (Loaded, and has entities)");

    public EntitiesMapCommand(String command) {
        super(command);
        setPermission("multipaper.command.entitiesmap");
    }

    @Override
    protected ChunkStatus getStatus(Player player, ChunkPos chunkPos) {
        ServerLevel level = ((CraftPlayer) player).getHandle().serverLevel();

        ChunkEntitySlices chunk = level.getEntityLookup().getChunk(chunkPos.x, chunkPos.z);

        if (chunk == null) return UNLOADED;

        if (chunk.isTransient()) return TRANSIENT;

        if (chunk.isEmpty()) return EMPTY;

        return LOADED;
    }
}
