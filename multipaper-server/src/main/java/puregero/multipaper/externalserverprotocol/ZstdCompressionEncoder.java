package puregero.multipaper.externalserverprotocol;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.nio.ByteBuffer;

public class ZstdCompressionEncoder extends MessageToByteEncoder<ByteBuf> {
    public static final int COMPRESSION_LEVEL = 3;

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, ByteBuf byteBufDest) throws Exception {
        int i = byteBuf.readableBytes();
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBufDest);
        if (i < MultiPaperConfiguration.get().peerConnection.compressionThreshold) {
            friendlyByteBuf.writeVarInt(0);
            friendlyByteBuf.writeBytes(byteBuf);
        } else {
            friendlyByteBuf.writeVarInt(i);
            ByteBuffer buffer = Zstd.compress(
                    byteBuf.internalNioBuffer(byteBuf.readerIndex(), byteBuf.readableBytes()),
                    COMPRESSION_LEVEL
            );
            byteBufDest.writeBytes(buffer);
        }

    }
}
