package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.CreatureSpawnEvent;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperChunkHandler;

import java.util.UUID;

public class PistonMoveBlockEndPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PistonMoveBlockEndPacket.class.getSimpleName());

    public static void finishBlockMove(ServerLevel level, BlockPos from, BlockPos to, Direction pistonDir, boolean retract, BlockState blockState, boolean isExternal) {
        LevelChunk chunk = level.getChunkIfLoaded(to);

        if (!MultiPaper.isChunkExternal(chunk)) {
            if (level.getBlockEntity(to) instanceof PistonMovingBlockEntity pistonMovingBlockEntity && pistonMovingBlockEntity.movedState.is(Blocks.AIR)) {
                pistonMovingBlockEntity.movedState = blockState;
            } else if (level.getBlockState(to).is(Blocks.AIR) || level.getBlockState(to).is(Blocks.PISTON_HEAD)) {
                BlockState movingPiston = Blocks.MOVING_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, pistonDir);
                MultiPaperChunkHandler.blockUpdateChunk = chunk; // Don't call onRemove on the piston head
                level.setBlock(to, movingPiston, 68);
                MultiPaperChunkHandler.blockUpdateChunk = null;
                level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(to, movingPiston, blockState, pistonDir, retract, false));
            } else {
                LootParams.Builder lootparams_a = (new LootParams.Builder(level)).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(to)).withParameter(LootContextParams.TOOL, new ItemStack(Items.NETHERITE_PICKAXE)).withOptionalParameter(LootContextParams.BLOCK_ENTITY, null);
                blockState.getDrops(lootparams_a).forEach(item -> {
                    LOGGER.info("Dropping " + item + " at " + to);
                    ItemEntity entity = new ItemEntity(level, to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5, item);
                    entity.pickupDelay = 10;
                    level.addFreshEntity(entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
                });
            }
            level.getChunkSource().blockChanged(to);

            if (isExternal) {
                chunk.playerChunk.broadcastChangesToOtherServers(chunk);
            }
            return;
        }

        // Ensure any changes have already been sent over before the server executes the piston move
        chunk.playerChunk.broadcastChangesToOtherServers(chunk);

        chunk.getChunkHolder().externalOwner.getConnection().send(new PistonMoveBlockEndPacket(level, from, to, pistonDir, retract, blockState));
    }

    private final UUID worldUUID;
    private final BlockPos from;
    private final BlockPos to;
    private final Direction pistonDir;
    private final boolean retract;
    private final BlockState blockState;

    public PistonMoveBlockEndPacket(ServerLevel level, BlockPos from, BlockPos to, Direction pistonDir, boolean retract, BlockState blockState) {
        this.worldUUID = level.getWorld().getUID();
        this.from = from;
        this.to = to;
        this.pistonDir = pistonDir;
        this.retract = retract;
        this.blockState = blockState;
    }

    public PistonMoveBlockEndPacket(FriendlyByteBuf in) {
        this.worldUUID = in.readUUID();
        this.from = in.readBlockPos();
        this.to = in.readBlockPos();
        this.pistonDir = in.readEnum(Direction.class);
        this.retract = in.readBoolean();
        this.blockState = in.readById(Block.BLOCK_STATE_REGISTRY);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(this.worldUUID);
        out.writeBlockPos(this.from);
        out.writeBlockPos(this.to);
        out.writeEnum(this.pistonDir);
        out.writeBoolean(this.retract);
        out.writeId(Block.BLOCK_STATE_REGISTRY, this.blockState);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World world = Bukkit.getWorld(this.worldUUID);

            if (!(world instanceof CraftWorld craftWorld)) {
                LOGGER.warn(connection.externalServer.getName() + " tried to move a block in world " + this.worldUUID + ", but we don't have that world loaded");
                return;
            }

            finishBlockMove(craftWorld.getHandle(), from, to, pistonDir, retract, blockState, true);
        });
    }
}
