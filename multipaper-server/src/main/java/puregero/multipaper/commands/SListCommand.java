package puregero.multipaper.commands;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import puregero.multipaper.ExternalPlayer;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.MultiPaper;

public class SListCommand extends Command {
    public SListCommand(String command) {
        super(command);
        setPermission("minecraft.command.list");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return false;

        sender.sendMessage(ChatColor.WHITE + "There are " + Bukkit.getAllOnlinePlayers().size() + " out of " + Bukkit.getMaxPlayers() + " players online");

        for (ExternalServer server : MultiPaper.getConnection().getServersMap().values()) {
            String name = ChatColor.GREEN + "[" + server.getName() + "] ";
            String playerList = ChatColor.WHITE + "";
            String playerSep = "";

            int playerCount = 0;

            for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().players) {
                if ((server.isMe() && MultiPaper.isRealPlayer(player))
                        || (player instanceof ExternalPlayer && ((ExternalPlayer) player).externalServerConnection == server.getConnection())) {
                    playerCount ++;
                    playerList += playerSep + player.getScoreboardName();
                    playerSep = ", ";
                }
            }

            String players = ChatColor.YELLOW + "(" + playerCount + "): ";

            if (!server.isAlive()) {
                if (playerCount == 0) {
                    continue;
                } else {
                    name = ChatColor.GRAY + "[" + server.getName() + "] ";
                }
            }

            sender.sendMessage(name + players + playerList);
        }

        return true;
    }
}
