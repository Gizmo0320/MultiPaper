package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class PacketConsolidationHandler extends ChannelDuplexHandler {
    private final int MAX_BUFFER_SIZE = 2 * 1024 * 1024;
    private final Executor consolidationDelay = CompletableFuture.delayedExecutor(MultiPaperConfiguration.get().peerConnection.consolidationDelay, TimeUnit.MILLISECONDS);
    private CompletableFuture<Void> consolidationFuture;
    private ChannelPromise bufferPromise;
    private ByteBuf buffer;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (buffer != null && (buffer.readableBytes() > MAX_BUFFER_SIZE || buffer.readableBytes() + ((ByteBuf) msg).readableBytes() > MAX_BUFFER_SIZE)) {
            ctx.writeAndFlush(buffer, bufferPromise);
            buffer = null;
        }

        if (((ByteBuf) msg).readableBytes() > MAX_BUFFER_SIZE) {
            ctx.writeAndFlush(msg, promise);
            return;
        }

        if (buffer == null) {
            buffer = ctx.alloc().buffer();
            bufferPromise = new DefaultChannelPromise(ctx.channel());
        }

        buffer.writeBytes((ByteBuf) msg);
        bufferPromise.addListener(p -> {
            if (p.isSuccess()) {
                promise.setSuccess();
            } else if (p.cause() != null) {
                promise.setFailure(p.cause());
            }
        });
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (consolidationFuture == null || consolidationFuture.isDone()) {
            consolidationFuture = new CompletableFuture<>();
            consolidationDelay.execute(() -> {
                ctx.channel().eventLoop().execute(() -> {
                    if (buffer != null) {
                        ctx.write(buffer, bufferPromise);
                        ctx.flush();
                    }
                    buffer = null;
                    bufferPromise = null;
                    consolidationFuture.complete(null);
                });
            });
        }
    }
}
