package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;

public class ExternalServerPacketEncoder extends MessageToByteEncoder<ExternalServerPacket> {
    @Override
    protected void encode(ChannelHandlerContext ctx, ExternalServerPacket msg, ByteBuf out) throws Exception {
        try {
            int packetId = ExternalServerPacketSerializer.getPacketId(msg);

            FriendlyByteBuf byteBuf = new FriendlyByteBuf(out);
            byteBuf.writeVarInt(packetId);
            msg.write(byteBuf);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
}
