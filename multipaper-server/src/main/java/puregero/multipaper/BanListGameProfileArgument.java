package puregero.multipaper;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserBanListEntry;

import java.util.Collections;

public class BanListGameProfileArgument extends GameProfileArgument {
    @Override
    public GameProfileArgument.Result parse(StringReader stringReader) throws CommandSyntaxException {
        int cursor = stringReader.getCursor();

        while(stringReader.canRead() && stringReader.peek() != ' ') {
            stringReader.skip();
        }

        String username = stringReader.getString().substring(cursor, stringReader.getCursor());

        for (UserBanListEntry entry : MinecraftServer.getServer().getPlayerList().getBans().getEntries()) {
            if (entry.getUser() != null && entry.getUser().getName() != null && entry.getUser().getName().equalsIgnoreCase(username)) {
                return (source) -> Collections.singleton(entry.getUser());
            }
        }

        stringReader.setCursor(cursor);

        return super.parse(stringReader);
    }
}
