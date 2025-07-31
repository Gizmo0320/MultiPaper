package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PistonMoveBlockStartPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PistonMoveBlockStartPacket.class.getSimpleName());

    public static void startBlockMove(ServerLevel level, BlockPos from, BlockPos to, Direction pistonDir, boolean retract) {
        startBlockMove(level, from, to, pistonDir, retract, false);
    }

    public static void startBlockMove(ServerLevel level, BlockPos from, BlockPos to, Direction pistonDir, boolean retract, boolean isExternal) {
        LevelChunk fromChunk = level.getChunkIfLoaded(from);
        LevelChunk toChunk = level.getChunkIfLoaded(to);

        if (MultiPaper.isChunkExternal(fromChunk) && !MultiPaper.isChunkExternal(toChunk)) {
            // Place a moving piston head in our chunk so that it can start ticking and maintain proper timing
            BlockState movingPiston = Blocks.MOVING_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, pistonDir);
            level.setBlock(to, movingPiston, 68);
            level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(to, movingPiston, Blocks.AIR.defaultBlockState(), pistonDir, retract, false));
            level.getChunkSource().blockChanged(to);
        }

        if (!MultiPaper.isChunkExternal(fromChunk)) {
            BlockState blockState = level.getBlockState(from);
            if (PistonBaseBlock.isPushable(blockState, level, from, retract ? pistonDir : pistonDir.getOpposite(), true, pistonDir)) {
                PistonMoveBlockEndPacket.finishBlockMove(level, from, to, pistonDir, retract, blockState, isExternal);
                level.setBlock(from, Blocks.AIR.defaultBlockState(), 2 | 4 | 16 | 1024);
                level.getChunkSource().blockChanged(from);
            }

            if (isExternal) {
                fromChunk.playerChunk.broadcastChangesToOtherServers(fromChunk);
            }

            return;
        }

        // Ensure any changes have already been sent over before the server executes the piston move
        fromChunk.playerChunk.broadcastChangesToOtherServers(fromChunk);
        toChunk.playerChunk.broadcastChangesToOtherServers(toChunk);

        fromChunk.getChunkHolder().externalOwner.getConnection().send(new PistonMoveBlockStartPacket(level, from, to, pistonDir, retract));
    }

    private final UUID worldUUID;
    private final BlockPos from;
    private final BlockPos to;
    private final Direction pistonDir;
    private final boolean retract;

    public PistonMoveBlockStartPacket(ServerLevel level, BlockPos from, BlockPos to, Direction pistonDir, boolean retract) {
        this.worldUUID = level.getWorld().getUID();
        this.from = from;
        this.to = to;
        this.pistonDir = pistonDir;
        this.retract = retract;
    }

    public PistonMoveBlockStartPacket(FriendlyByteBuf in) {
        this.worldUUID = in.readUUID();
        this.from = in.readBlockPos();
        this.to = in.readBlockPos();
        this.pistonDir = in.readEnum(Direction.class);
        this.retract = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(this.worldUUID);
        out.writeBlockPos(this.from);
        out.writeBlockPos(this.to);
        out.writeEnum(this.pistonDir);
        out.writeBoolean(this.retract);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World world = Bukkit.getWorld(this.worldUUID);

            if (!(world instanceof CraftWorld craftWorld)) {
                LOGGER.warn(connection.externalServer.getName() + " tried to move a block in world " + this.worldUUID + ", but we don't have that world loaded");
                return;
            }

            startBlockMove(craftWorld.getHandle(), from, to, pistonDir, retract, true);
        });
    }
}
