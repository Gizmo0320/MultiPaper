package puregero.multipaper.externalserverprotocol;

import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.SignedMessageLink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class AddPendingMessagePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(AddPendingMessagePacket.class.getSimpleName());

    private static final Map<PlayerChatMessage, Set<UUID>> chatMessagesToFlush = new ConcurrentHashMap<>();
    private static CompletableFuture<Void> flushTask = CompletableFuture.completedFuture(null);
    public static void broadcastAddMessage(PlayerChatMessage message, UUID uuid) {
        // Buffer all uuids for a single chat message so that we aren't sending the entire message for every player

        chatMessagesToFlush.compute(message, (key, uuidSet) -> {
            if (uuidSet == null) {
                uuidSet = new HashSet<>();
            }
            uuidSet.add(uuid);
            return uuidSet;
        });

        if (flushTask.isDone()) {
            flushTask = CompletableFuture.runAsync(AddPendingMessagePacket::flush, CompletableFuture.delayedExecutor(1, TimeUnit.MILLISECONDS));
        }
    }

    public static void flush() {
        synchronized (chatMessagesToFlush) {
            // Synchronized to prevent concurrent flushes (concurrent additions are fine)
            Iterator<Map.Entry<PlayerChatMessage, Set<UUID>>> iterator = chatMessagesToFlush.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<PlayerChatMessage, Set<UUID>> entry = iterator.next();
                iterator.remove();
                PlayerChatMessage message = entry.getKey();
                Set<UUID> uuids = entry.getValue();

                MultiPaper.broadcastPacketToExternalServers(new AddPendingMessagePacket(message, uuids));
            }
        }
    }

    private final Collection<UUID> uuids;
    private final int index;
    private final UUID sender;
    private final UUID sessionId;
    private final MessageSignature messageSignature;
    private final String content;
    private final Instant timeStamp;
    private final long salt;
    private final List<MessageSignature> lastSeen;
    private final Component unsignedContent;
    private final FilterMask filterMask;

    public AddPendingMessagePacket(PlayerChatMessage message, Set<UUID> uuids) {
        this.uuids = uuids;

        this.index = message.link().index();
        this.sender = message.link().sender();
        this.sessionId = message.link().sessionId();
        this.messageSignature = message.signature();
        this.content = message.signedBody().content();
        this.timeStamp = message.signedBody().timeStamp();
        this.salt = message.signedBody().salt();
        this.lastSeen = message.signedBody().lastSeen().entries();
        this.unsignedContent = message.unsignedContent();
        this.filterMask = message.filterMask();
    }

    public AddPendingMessagePacket(FriendlyByteBuf in) {
        this.uuids = in.readList(FriendlyByteBuf::readUUID);

        this.index = in.readInt();
        this.sender = in.readUUID();
        this.sessionId = in.readNullable(FriendlyByteBuf::readUUID);
        this.messageSignature = in.readNullable(MessageSignature::read);
        this.content = in.readUtf();
        this.timeStamp = in.readInstant();
        this.salt = in.readLong();
        this.lastSeen = in.readList(MessageSignature::read);
        this.unsignedContent = in.readNullable(FriendlyByteBuf::readComponent);
        this.filterMask = FilterMask.read(in);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeCollection(this.uuids, FriendlyByteBuf::writeUUID);

        out.writeInt(this.index);
        out.writeUUID(this.sender);
        out.writeNullable(this.sessionId, FriendlyByteBuf::writeUUID);
        out.writeNullable(this.messageSignature, MessageSignature::write);
        out.writeUtf(this.content);
        out.writeInstant(this.timeStamp);
        out.writeLong(this.salt);
        out.writeCollection(this.lastSeen, MessageSignature::write);
        out.writeNullable(this.unsignedContent, FriendlyByteBuf::writeComponent);
        FilterMask.write(out, this.filterMask);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        PlayerChatMessage chatMessage = new PlayerChatMessage(
                new SignedMessageLink(this.index, this.sender, this.sessionId == null ? Util.NIL_UUID : this.sessionId),
                this.messageSignature,
                new SignedMessageBody(this.content, this.timeStamp, this.salt, new LastSeenMessages(this.lastSeen)),
                this.unsignedContent,
                this.filterMask
        );

        for (UUID uuid : uuids) {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.connection.addPendingMessage(chatMessage, true);
            } else {
                LOGGER.warn("Could not find player " + uuid);
            }
        }
    }
}
