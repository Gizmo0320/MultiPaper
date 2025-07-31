package puregero.multipaper;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.CallDataStorageMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.KeyValueStringMapMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.NullableStringMessageReply;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class CraftMultiPaperDataStorage implements MultiPaperDataStorage {
    @Override
    public CompletableFuture<String> get(String key) {
        return MultiPaper.getConnection()
                .sendAndAwaitReply(new CallDataStorageMessage(key, CallDataStorageMessage.Action.GET, null), NullableStringMessageReply.class)
                .thenApply(reply -> reply.result);
    }

    @Override
    public CompletableFuture<Map<String, String>> list(String prefix) {
        return MultiPaper.getConnection()
                .sendAndAwaitReply(new CallDataStorageMessage(prefix == null ? "" : prefix, CallDataStorageMessage.Action.LIST, null), KeyValueStringMapMessageReply.class)
                .thenApply(reply -> reply.result);
    }

    @Override
    public CompletableFuture<String> set(String key, String value) {
        return MultiPaper.getConnection()
                .sendAndAwaitReply(new CallDataStorageMessage(key, CallDataStorageMessage.Action.SET, value), NullableStringMessageReply.class)
                .thenApply(reply -> reply.result);
    }

    @Override
    public CompletableFuture<String> add(String key, String increment) {
        return MultiPaper.getConnection()
                .sendAndAwaitReply(new CallDataStorageMessage(key, CallDataStorageMessage.Action.ADD, increment), NullableStringMessageReply.class)
                .thenApply(reply -> reply.result);
    }
}
