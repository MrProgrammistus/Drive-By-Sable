package edn.lakeopossmc.drivebysabletweaks.blocks;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllItems;
import edn.stratodonut.drivebywire.WireSounds;
import edn.stratodonut.drivebywire.compat.LinkedControllerWireServerHandler;
import edn.stratodonut.drivebywire.util.HubItem;
import edn.stratodonut.drivebywire.wire.MultiChannelWireSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

public class DirectionalControllerHubBlock extends FaceAttachedHorizontalDirectionalBlock implements MultiChannelWireSource {
    protected static final VoxelShape NORTH_AABB = Block.box(0.0, 0.0, 8.0, 16.0, 16.0, 16.0);
    protected static final VoxelShape SOUTH_AABB = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 8.0);
    protected static final VoxelShape WEST_AABB = Block.box(8.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    protected static final VoxelShape EAST_AABB = Block.box(0.0, 0.0, 0.0, 8.0, 16.0, 16.0);
    protected static final VoxelShape UP_AABB_Z = Block.box(0.0, 0.0, 0.0, 16.0, 8.0, 16.0);
    protected static final VoxelShape UP_AABB_X = Block.box(0.0, 0.0, 0.0, 16.0, 8.0, 16.0);
    protected static final VoxelShape DOWN_AABB_Z = Block.box(0.0, 8.0, 0.0, 16.0, 16.0D, 16.0);
    protected static final VoxelShape DOWN_AABB_X = Block.box(0.0, 8.0, 0.0, 16.0, 16.0D, 16.0);
    private static final List<String> CHANNELS = Arrays.stream(LinkedControllerWireServerHandler.KEY_TO_CHANNEL).toList();

    public DirectionalControllerHubBlock(final Properties properties) {
        super(properties);
        // default state for init
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(FACE, AttachFace.FLOOR));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // registers properties for directions
        builder.add(FACING, FACE);
    }

    @Override
    protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() {
        return MapCodec.unit(() -> this);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        for (Direction direction : context.getNearestLookingDirections()) {
            BlockState blockstate;
            if (direction.getAxis() == Direction.Axis.Y) {
                blockstate = this.defaultBlockState()
                        .setValue(FACE, direction == Direction.UP ? AttachFace.CEILING : AttachFace.FLOOR)
                        .setValue(FACING, context.getHorizontalDirection());
            } else {
                blockstate = this.defaultBlockState().setValue(FACE, AttachFace.WALL).setValue(FACING, direction.getOpposite());
            }

            if (blockstate.canSurvive(context.getLevel(), context.getClickedPos())) {
                return blockstate;
            }
        }

        return null;
    }

    @Override
    protected ItemInteractionResult useItemOn(
        final ItemStack itemStack,
        final BlockState state,
        final Level level,
        final BlockPos blockPos,
        final Player player,
        final InteractionHand interactionHand,
        final BlockHitResult hitResult
    ) {
        if (!AllItems.LINKED_CONTROLLER.isIn(itemStack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!level.isClientSide()) {
            HubItem.putHub(itemStack, blockPos);
            level.playSound(null, blockPos, WireSounds.PLUG_IN.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
            player.displayClientMessage(Component.literal("Controller connected!"), true);
        }

        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        switch ((AttachFace)state.getValue(FACE)) {
            case FLOOR:
                switch (state.getValue(FACING).getAxis()) {
                    case X:
                        return UP_AABB_X;
                    case Z:
                    default:
                        return UP_AABB_Z;
                }
            case WALL:
                switch ((Direction)state.getValue(FACING)) {
                    case EAST:
                        return EAST_AABB;
                    case WEST:
                        return WEST_AABB;
                    case SOUTH:
                        return SOUTH_AABB;
                    case NORTH:
                    default:
                        return NORTH_AABB;
                }
            case CEILING:
            default:
                switch (state.getValue(FACING).getAxis()) {
                    case X:
                        return DOWN_AABB_X;
                    case Z:
                    default:
                        return DOWN_AABB_Z;
                }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level, BlockPos currentPos, BlockPos facingPos) { return state; }

    @Override
    public List<String> wire$getChannels() {
        return CHANNELS;
    }

    @Override
    public String wire$nextChannel(final String current, final boolean forward) {
        final int currentIndex = CHANNELS.indexOf(current);
        if (currentIndex == -1) {
            return CHANNELS.getFirst();
        }
        return CHANNELS.get(Math.floorMod(currentIndex + (forward ? 1 : -1), CHANNELS.size()));
    }
}
