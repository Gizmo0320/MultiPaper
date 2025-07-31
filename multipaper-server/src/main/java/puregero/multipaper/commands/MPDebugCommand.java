package puregero.multipaper.commands;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.MultiPaper;

import java.util.HashSet;
import java.util.Iterator;

public class MPDebugCommand extends Command implements Runnable {
    private final HashSet<Player> debugEnabled = new HashSet<>();
    private BukkitTask task = null;

    public MPDebugCommand(String command) {
        super(command);
        setPermission("multipaper.command.mpdebug");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, String[] args) {
        if (!testPermission(sender)) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can execute this command.");
            return false;
        }

        if (debugEnabled.remove(player)) {
            player.sendMessage("MultiPaper debug disabled");
            return false;
        }

        debugEnabled.add(player);

        sender.sendMessage("MultiPaper debug enabled");

        if (task == null) {
            run();
        }

        return true;
    }

    @Override
    public void run() {
        task = null;

        Iterator<Player> iterator = debugEnabled.iterator();
        while (iterator.hasNext()) {
            Player player = iterator.next();

            if (!player.isOnline()) {
                iterator.remove();
                continue;
            }

            run(player);
        }

        if (!debugEnabled.isEmpty()) {
            task = ((CraftScheduler) Bukkit.getScheduler()).scheduleInternalTask(this, 5, "MultiPaper debug");
        }
    }

    private void run(Player player) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(((CraftPlayer) player).getHandle());

        if (newChunkHolder != null) {
            ExternalServer owner = newChunkHolder.externalOwner;
            player.sendActionBar(Component.text(owner == null ? "null" : owner.getName()).color(owner == null ? NamedTextColor.WHITE : (owner.isMe() ? NamedTextColor.AQUA : NamedTextColor.RED)));
        }

        for (double x = -2; x <= 2; x += 0.5) {
            for (double z = -2; z <= 2; z += 0.5) {
                Vec3 vec = ((CraftPlayer) player).getHandle().position().add(x, 0.5, z);
                BlockPos pos = new BlockPos((int) vec.x, (int) vec.y, (int) vec.z);
                LevelChunk levelChunk = ((CraftWorld) player.getWorld()).getHandle().getChunkIfLoaded(pos);
                Color color;
                if (MultiPaper.isChunkExternal(levelChunk)) {
                    color = Color.RED;
                } else if (MultiPaper.isChunkLocal(levelChunk)) {
                    color = Color.AQUA;
                } else {
                    // Chunk has no owner, this shouldn't be possible if the player's right up in its face
                    color = Color.WHITE;
                }
                player.spawnParticle(Particle.REDSTONE, vec.x, vec.y, vec.z, 1, new Particle.DustOptions(color, 1));
            }
        }
    }
}
