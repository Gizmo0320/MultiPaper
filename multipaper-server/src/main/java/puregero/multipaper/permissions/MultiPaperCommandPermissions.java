package puregero.multipaper.permissions;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.permissions.DefaultPermissions;
import org.jetbrains.annotations.NotNull;

public class MultiPaperCommandPermissions {
    private static final String ROOT = "multipaper.command";
    private static final String PREFIX = ROOT + ".";

    public static void registerPermissions(@NotNull Permission parent) {
        Permission commands = DefaultPermissions.registerPermission(ROOT, "Gives the user the ability to use all MultiPaper commands", parent);

        DefaultPermissions.registerPermission(PREFIX + "servers", "List details about servers running on this MultiPaper network", PermissionDefault.TRUE, commands);
        DefaultPermissions.registerPermission(PREFIX + "mpdebug", "MPDebug command", PermissionDefault.TRUE, commands);
        DefaultPermissions.registerPermission(PREFIX + "mpmap", "MPMap command", PermissionDefault.TRUE, commands);
        DefaultPermissions.registerPermission(PREFIX + "entitiesmap", "Entitiesmap command", PermissionDefault.OP, commands);

        commands.recalculatePermissibles();
    }
}
