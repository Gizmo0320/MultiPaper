package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.function.Function;

public class ExternalServerPacketDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> list) throws Exception {
        try {
            int i = byteBuf.readableBytes();
            if (i != 0) {
                FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
                int packetId = friendlyByteBuf.readVarInt();
                Function<FriendlyByteBuf, ExternalServerPacket> deserializer = ExternalServerPacketSerializer.getDeserializer(packetId);
                list.add(deserializer.apply(friendlyByteBuf));
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
}
