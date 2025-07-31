package puregero.multipaper.externalserverprotocol;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.ByteBuffer;
import java.util.List;

public class ZstdCompressionDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        if (byteBuf.readableBytes() != 0) {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int length = friendlyByteBuf.readVarInt();
            if (length == 0) {
                list.add(byteBuf.readBytes(byteBuf.readableBytes()));
            } else {
                ByteBuffer buffer = Zstd.decompress(
                        byteBuf.nioBuffer(),
                        length
                );
                byteBuf.readerIndex(byteBuf.readerIndex() + byteBuf.readableBytes());
                list.add(Unpooled.wrappedBuffer(buffer));
            }
        }
    }
}
