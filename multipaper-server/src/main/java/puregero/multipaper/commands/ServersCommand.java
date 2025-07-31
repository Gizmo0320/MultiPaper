package puregero.multipaper.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import puregero.multipaper.ExternalPlayer;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.MultiPaper;

public class ServersCommand extends Command {
    public ServersCommand(String command) {
        super(command);
        setPermission("multipaper.command.servers");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return false;

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<gray>[Servers] (Avg Tick Time / Tps / Player Count)");

        for (ExternalServer server : MultiPaper.getConnection().getServersMap().values()) {
            String tickTime = server.getAverageTickTime() + "ms";
            if (server.getTps() >= 19.9) {
                tickTime = "<green>" +tickTime;
            } else if (server.getTps() >= 17.5) {
                tickTime = "<yellow>" + tickTime;
            } else {
                tickTime = "<red>" + tickTime;
            }

            String tpsString = String.format("%.1f tps", server.getTps());

            int players = 0;

            for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().players) {
                if ((server.isMe() && MultiPaper.isRealPlayer(player))
                        || (player instanceof ExternalPlayer && ((ExternalPlayer) player).externalServerConnection == server.getConnection())) {
                    players ++;
                }
            }

            String playersString = players + " player";
            if (players != 1) {
                playersString += "s";
            }

            if (!server.isAlive()) {
                stringBuilder.append(String.format("\n<dark_gray>[%s] %sms, %s, %s", server.getName(), server.getAverageTickTime(), tpsString, playersString));
            } else {
                stringBuilder.append(String.format("\n<green>[%s<green>] %s<green>, %s, %s",
                        (server.isMe() ? "<gold>" : "") + server.getName(), tickTime, tpsString, playersString));
            }
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(stringBuilder.toString()));
        return true;
    }
}
