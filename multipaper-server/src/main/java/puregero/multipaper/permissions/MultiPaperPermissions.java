package puregero.multipaper.permissions;

import org.bukkit.permissions.Permission;
import org.bukkit.util.permissions.DefaultPermissions;

public class MultiPaperPermissions {
    private static final String ROOT = "multipaper";

    public static void registerCorePermissions() {
        Permission parent = DefaultPermissions.registerPermission(ROOT, "Gives the user the ability to use all MultiPaper utilities and commands");

        MultiPaperCommandPermissions.registerPermissions(parent);

        parent.recalculatePermissibles();
    }
}
