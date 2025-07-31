package puregero.multipaper.externalserverprotocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;

import java.net.SocketException;

public class ExternalServerPacketHandler extends SimpleChannelInboundHandler<ExternalServerPacket> {
    private static final Logger LOGGER = LogManager.getLogger(ExternalServerPacketHandler.class.getSimpleName());
    private final ExternalServerConnection connection;
    private boolean disconnectedWithException = false;

    public ExternalServerPacketHandler(ExternalServerConnection connection) {
        this.connection = connection;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ExternalServerPacket msg) {
        connection.lastPacketReceived = System.currentTimeMillis();
        msg.handle(connection);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
        if (ctx.channel().isOpen()) {
            if (throwable instanceof SocketException) {
                disconnectedWithException = true;
                if (connection.externalServer != null) {
                    LOGGER.info("External server " + connection.externalServer.getName() + " has disconnected: " + throwable.getMessage());
                }
            } else {
                throwable.printStackTrace();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connection.nanoTime = 0;
        if (!disconnectedWithException && connection.externalServer != null) {
            LOGGER.info("External server " + connection.externalServer.getName() + " has disconnected");
        }
    }
}
