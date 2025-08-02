package puregero.multipaper.permissions;


import org.bukkit.permissions.Permission;
import org.bukkit.util.permissions.DefaultPermissions;

public class MultiPaperPermissions {

    private static final String ROOT = "multipaper";

    public static void registerCorePermissions(final Permission parent) {

        final Permission root = DefaultPermissions.registerPermission(ROOT, "Gives the user the ability to use all MultiPaper features", parent);

        DefaultPermissions.registerPermission(ROOT + ".chunk", "Gives the user the ability to use all MultiPaper chunk features", root);
        DefaultPermissions.registerPermission(ROOT + ".chunk.region", "Gives the user the ability to use all MultiPaper region chunk features", root);
        DefaultPermissions.registerPermission(ROOT + ".chunk.region.create", "Gives the user the ability to create MultiPaper regions", root);
        DefaultPermissions.registerPermission(ROOT + ".chunk.region.delete", "Gives the user the ability to delete MultiPaper regions", root);
    }
}
