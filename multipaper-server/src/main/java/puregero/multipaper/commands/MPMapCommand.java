package puregero.multipaper.commands;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.entity.Player;
import puregero.multipaper.MultiPaper;

public class MPMapCommand extends MapCommandBase {

    public MPMapCommand(final String command) {
        super(command);
        setPermission("multipaper.command.mpmap");
    }

    @Override
    protected ChunkStatus getStatus(final Player player, final ChunkPos chunkPos) {
        final NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(player.getWorld().getName(), chunkPos.x, chunkPos.z);

        final String name = newChunkHolder == null || newChunkHolder.externalOwner == null? null : newChunkHolder.externalOwner.getName();
        final NamedTextColor color = newChunkHolder == null? NamedTextColor.DARK_GRAY : (newChunkHolder.externalOwner == null? NamedTextColor.WHITE : (newChunkHolder.externalOwner.isMe()? NamedTextColor.AQUA : NamedTextColor.RED));

        return new ChunkStatus(color, name);
    }
}
