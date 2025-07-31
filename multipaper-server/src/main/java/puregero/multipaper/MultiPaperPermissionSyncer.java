package puregero.multipaper;


import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.HashMap;
import java.util.HashSet;

public class MultiPaperPermissionSyncer {

    private static final HashMap<Player, MultiPaperPermissionSyncer> syncers = new HashMap<>();

    private final Player player;
    private final HashMap<String, Boolean> permissions = new HashMap<>();

    public MultiPaperPermissionSyncer(Player player) {
        this.player = player;
    }

    public static void sync() {
        if (!MultiPaperConfiguration.get().syncSettings.syncPermissions) {
            return;
        }

        for (Player player : Bukkit.getAllOnlinePlayers()) {
            syncers.computeIfAbsent(player, MultiPaperPermissionSyncer::new);
        }

        syncers.values().removeIf(MultiPaperPermissionSyncer::tick);
    }

    private boolean tick() {
        if (!player.isOnline()) {
            return true;
        }

        if (((CraftPlayer) player).perm.dirty) {
            ((CraftPlayer) player).perm.dirty = false;
            HashSet<String> visitedPermissions = new HashSet<>();

            player.getEffectivePermissions().forEach(info -> {
                visitedPermissions.add(info.getPermission());
                if (!permissions.containsKey(info.getPermission()) || permissions.get(info.getPermission()) != info.getValue()) {
                    player.setData("permission." + info.getPermission(), Boolean.toString(info.getValue()));
                    permissions.put(info.getPermission(), info.getValue());
                }
            });

            permissions.entrySet().removeIf(entry -> {
                if (!visitedPermissions.contains(entry.getKey())) {
                    player.setData("permission." + entry.getKey(), null);

                    return true;
                } else {
                    return false;
                }
            });
        }

        return false;
    }

}
