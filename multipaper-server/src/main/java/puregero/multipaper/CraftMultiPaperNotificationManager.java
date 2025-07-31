package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.MultiPaperNotificationManager;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import puregero.multipaper.externalserverprotocol.PluginNotificationPacket;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

public class CraftMultiPaperNotificationManager implements MultiPaperNotificationManager {

    private final HashMap<String, List<Listener>> listeners = new HashMap<>();

    @Override
    public void on(Plugin plugin, String channel, Consumer<byte[]> callback) {
        on(plugin, channel, (data, replyFunction) -> callback.accept(data));
    }

    @Override
    public void on(Plugin plugin, String channel, BiConsumer<byte[], BiConsumer<String, byte[]>> callbackWithReply) {
        listeners.computeIfAbsent(channel, key -> new ArrayList<>()).add(new Listener(plugin, callbackWithReply));
    }

    @Override
    public void notify(String channel, byte[] data) {
        MultiPaper.broadcastPacketToExternalServers(new PluginNotificationPacket(channel, data));
    }

    @Override
    public void notify(Chunk chunk, String channel, byte[] data) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(((CraftWorld) chunk.getWorld()).getHandle(), chunk.getX(), chunk.getZ());

        if (newChunkHolder == null) {
            new IllegalStateException("Chunk " + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ() + " is not loaded, could not send notification").printStackTrace();
            return;
        }

        MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new PluginNotificationPacket(channel, data));
    }

    @Override
    public void notifyOwningServer(Chunk chunk, String channel, byte[] data) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(((CraftWorld) chunk.getWorld()).getHandle(), chunk.getX(), chunk.getZ());

        if (newChunkHolder == null) {
            new IllegalStateException("Chunk " + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ() + " is not loaded, could not send notification").printStackTrace();
            return;
        }

        if (newChunkHolder.externalOwner == null) {
            onNotification(null, channel, data); // Send to ourselves
        } else {
            MultiPaper.broadcastPacketToExternalServers(Collections.singleton(newChunkHolder.externalOwner), () -> new PluginNotificationPacket(channel, data));
        }
    }

    @Override
    public void notifyOwningServer(Player bukkitPlayer, String channel, byte[] data) {
        if (bukkitPlayer instanceof CraftPlayer craftPlayer && craftPlayer.getHandle() instanceof ExternalPlayer externalPlayer) {
            MultiPaper.broadcastPacketToExternalServers(Collections.singleton(externalPlayer.externalServerConnection.externalServer), () -> new PluginNotificationPacket(channel, data));
        } else {
            onNotification(null, channel, data); // Send to ourselves
        }
    }

    public void onNotification(ExternalServerConnection sender, String channel, byte[] data) {
        List<Listener> listenerList = listeners.get(channel);
        if (listenerList != null) {
            Iterator<Listener> iterator = listenerList.iterator();

            while (iterator.hasNext()) {
                Listener listener = iterator.next();
                Plugin plugin = listener.plugin.get();

                if (plugin == null || !plugin.isEnabled()) {
                    // Remove disabled plugins
                    iterator.remove();
                } else {
                    try {
                        listener.consumer.accept(data, (replyChannel, replyData) -> {
                            if (sender == null) {
                                onNotification(null, replyChannel, replyData); // Replying to ourselves
                            } else {
                                sender.send(new PluginNotificationPacket(replyChannel, replyData));
                            }
                        });
                    } catch (Throwable ex) {
                        String msg = "Could not pass notification " + channel + " to " + plugin.getName();
                        Bukkit.getLogger().log(Level.SEVERE, msg, ex);
                    }
                }
            }

            if (listenerList.isEmpty()) {
                listeners.remove(channel);
            }
        }
    }

    private static class Listener {
        // Weak reference the plugin so that we don't keep an entire plugin loaded in memory after a reload
        private final WeakReference<Plugin> plugin;
        private final BiConsumer<byte[], BiConsumer<String, byte[]>> consumer;

        public Listener(Plugin plugin, BiConsumer<byte[], BiConsumer<String, byte[]>> consumer) {
            this.plugin = new WeakReference<>(plugin);
            this.consumer = consumer;
        }
    }
}
