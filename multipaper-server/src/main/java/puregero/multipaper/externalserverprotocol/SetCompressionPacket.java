package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.mastermessagingprotocol.MessageLengthDecoder;

public class SetCompressionPacket extends ExternalServerPacket {

    public static final int ZLIB_COMPRESSION = 1;
    public static final int ZSTD_COMPRESSION = 2;

    private final int compressionType;

    public SetCompressionPacket(int compressionType) {
        this.compressionType = compressionType;
    }

    public SetCompressionPacket(FriendlyByteBuf in) {
        compressionType = in.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeVarInt(compressionType);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        connection.getChannel().pipeline()
                .addFirst("decompresser", switch (compressionType) {
                    case ZLIB_COMPRESSION -> createZlibCompressionDecoder();
                    case ZSTD_COMPRESSION -> createZstdCompressionDecoder();
                    default -> throw new IllegalArgumentException("Unknown compression type of " + compressionType);
                })
                .addFirst("splitter", new MessageLengthDecoder());
    }

    public static ZlibCompressionDecoder createZlibCompressionDecoder() {
        return new ZlibCompressionDecoder(com.velocitypowered.natives.util.Natives.compress.get().create(-1));
    }

    public static ZlibCompressionEncoder createZlibCompressionEncoder() {
        return new ZlibCompressionEncoder(com.velocitypowered.natives.util.Natives.compress.get().create(-1));
    }

    public static ZstdCompressionDecoder createZstdCompressionDecoder() {
        return new ZstdCompressionDecoder();
    }

    public static ZstdCompressionEncoder createZstdCompressionEncoder() {
        return new ZstdCompressionEncoder();
    }
}
