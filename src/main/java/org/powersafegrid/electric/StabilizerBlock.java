package org.powersafegrid.electric;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.HorizontalElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.wire.powercord.IAcceptCord;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

@MethodsReturnNonnullByDefault
public class StabilizerBlock extends HorizontalElectricBlock implements IBE<StabilizerBlockEntity>, IAcceptCord {

    public static final BooleanProperty TRIPPED = BooleanProperty.create("tripped");

    private final float targetVoltage;
    private final float minVoltage;
    private final float maxVoltage;

    public static final Component TERMINAL_INPUT_L = VoltageRelayBlock.TERMINAL_INPUT_L;
    public static final Component TERMINAL_INPUT_N = VoltageRelayBlock.TERMINAL_INPUT_N;
    public static final Component TERMINAL_OUTPUT_L = VoltageRelayBlock.TERMINAL_OUTPUT_L;
    public static final Component TERMINAL_OUTPUT_N = VoltageRelayBlock.TERMINAL_OUTPUT_N;

    private static final VoxelShape SHAPE_NORTH = Shapes.or(
            Shapes.box(1 / 16.0, 0, 2 / 16.0, 15 / 16.0, 14 / 16.0, 14 / 16.0),
            Shapes.box(2 / 16.0, 5 / 16.0, 14 / 16.0, 5 / 16.0, 8 / 16.0, 1),
            Shapes.box(11 / 16.0, 5 / 16.0, 14 / 16.0, 14 / 16.0, 8 / 16.0, 1),
            Shapes.box(2 / 16.0, 5 / 16.0, 0, 5 / 16.0, 8 / 16.0, 2 / 16.0),
            Shapes.box(11 / 16.0, 5 / 16.0, 0, 14 / 16.0, 8 / 16.0, 2 / 16.0));

    public static final TerminalBoundingBox[] TERMINALS_NORTH = new TerminalBoundingBox[] {
            new TerminalBoundingBox(TERMINAL_INPUT_L, 2, 5, 14, 5, 8, 16),
            new TerminalBoundingBox(TERMINAL_INPUT_N, 11, 5, 14, 14, 8, 16),
            new TerminalBoundingBox(TERMINAL_OUTPUT_L, 2, 5, 0, 5, 8, 2),
            new TerminalBoundingBox(TERMINAL_OUTPUT_N, 11, 5, 0, 14, 8, 2),
    };

    public StabilizerBlock(Properties properties, float targetVoltage, float minVoltage, float maxVoltage) {
        super(properties);
        this.targetVoltage = targetVoltage;
        this.minVoltage = minVoltage;
        this.maxVoltage = maxVoltage;
        registerDefaultState(defaultBlockState().setValue(TRIPPED, false));
        setTerminalCollection(horizontalNorthTerminals(this, TERMINALS_NORTH, SHAPE_NORTH));
    }

    public float getTargetVoltage() {
        return targetVoltage;
    }

    public float getMinVoltage() {
        return minVoltage;
    }

    public float getMaxVoltage() {
        return maxVoltage;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TRIPPED);
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        var local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        ITerminalPlacement terminal = terminalAt(state, local);
        if (terminal != null)
            return InteractionResult.PASS;
        if (PSGTools.isResetTool(player.getItemInHand(hand))) {
            if (!world.isClientSide)
                withBlockEntityDo(world, pos, StabilizerBlockEntity::reset);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public Class<StabilizerBlockEntity> getBlockEntityClass() {
        return StabilizerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends StabilizerBlockEntity> getBlockEntityType() {
        return PSGBlockEntities.STABILIZER.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.stabilizer.1",
                String.format("%.0f", targetVoltage)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.stabilizer.2",
                String.format("%.0f", minVoltage), String.format("%.0f", maxVoltage)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.stabilizer.3").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.reset").withStyle(ChatFormatting.AQUA));
    }
}
