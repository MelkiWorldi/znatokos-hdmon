package com.znatok.hdmon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import com.znatok.hdmon.block.HDMonBlockEntity;

public class HDMonBlock extends BaseEntityBlock {
    public static final MapCodec<HDMonBlock> CODEC = simpleCodec(HDMonBlock::new);

    public HDMonBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,
                ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HDMonBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return HDMonBlockEntity.ticker(level, type);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide) fireTouch(state, level, pos, hit);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        // Mirror vanilla CC monitor: also fire touch when holding any item.
        if (!level.isClientSide) fireTouch(state, level, pos, hit);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void fireTouch(BlockState state, Level level, BlockPos pos, BlockHitResult hit) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        if (hit.getDirection() != facing) return; // only front face counts

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HDMonBlockEntity hd)) return;

        Vec3 loc = hit.getLocation();
        double lx = loc.x - pos.getX();
        double ly = loc.y - pos.getY();
        double lz = loc.z - pos.getZ();

        // u = horizontal along the face (0..1 left-to-right as viewed from the front)
        // v = vertical from top (0=top, 1=bottom)
        double u, v;
        switch (facing) {
            case SOUTH: u = lx;       v = 1.0 - ly; break;
            case NORTH: u = 1.0 - lx; v = 1.0 - ly; break;
            case WEST:  u = 1.0 - lz; v = 1.0 - ly; break;
            case EAST:  u = lz;       v = 1.0 - ly; break;
            default: return;
        }
        if (u < 0) u = 0; if (u > 0.9999) u = 0.9999;
        if (v < 0) v = 0; if (v > 0.9999) v = 0.9999;

        int col = hd.getColIndex();
        int row = hd.getRowIndex();
        int cols = Math.max(1, hd.getCols());
        int rows = Math.max(1, hd.getRows());

        int px = col * HDMonBlockEntity.WIDTH + (int) (u * HDMonBlockEntity.WIDTH);
        // row=0 is bottom; v=0 is top of whole screen when this is the top block.
        int py = (rows - 1 - row) * HDMonBlockEntity.HEIGHT + (int) (v * HDMonBlockEntity.HEIGHT);

        int maxX = cols * HDMonBlockEntity.WIDTH - 1;
        int maxY = rows * HDMonBlockEntity.HEIGHT - 1;
        if (px < 0) px = 0; if (px > maxX) px = maxX;
        if (py < 0) py = 0; if (py > maxY) py = maxY;

        BlockEntity obe = level.getBlockEntity(hd.getOriginPos());
        if (obe instanceof HDMonBlockEntity origin) {
            com.znatok.hdmon.peripheral.HDMonPeripheral p = origin.getPeripheral(null);
            p.fireTouch(px, py);
        }
    }
}
