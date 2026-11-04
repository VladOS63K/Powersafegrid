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
public class VoltageRelayBlock extends HorizontalElectricBlock implements IBE<VoltageRelayBlockEntity>, IAcceptCord {

    public static final BooleanProperty CLOSED = BooleanProperty.create("closed");

    public static final Component TERMINAL_INPUT_L = terminal("relay.input_l", ChatFormatting.RED);
    public static final Component TERMINAL_INPUT_N = terminal("relay.input_n", ChatFormatting.BLUE);
    public static final Component TERMINAL_OUTPUT_L = terminal("relay.output_l", ChatFormatting.RED);
    public static final Component TERMINAL_OUTPUT_N = terminal("relay.output_n", ChatFormatting.BLUE);

    private static final VoxelShape SHAPE_NORTH = Shapes.or(
            Shapes.box(2 / 16.0, 0, 2 / 16.0, 14 / 16.0, 12 / 16.0, 14 / 16.0),
            Shapes.box(2 / 16.0, 4 / 16.0, 14 / 16.0, 5 / 16.0, 7 / 16.0, 1),
            Shapes.box(11 / 16.0, 4 / 16.0, 14 / 16.0, 14 / 16.0, 7 / 16.0, 1),
            Shapes.box(2 / 16.0, 4 / 16.0, 0, 5 / 16.0, 7 / 16.0, 2 / 16.0),
            Shapes.box(11 / 16.0, 4 / 16.0, 0, 14 / 16.0, 7 / 16.0, 2 / 16.0));

    /**
     * Terminal order matches circuit node order: 0/1 - input (grid side),
     * 2/3 - output (load side). Input is on the back, output on the front.
     */
    public static final TerminalBoundingBox[] TERMINALS_NORTH = new TerminalBoundingBox[] {
            new TerminalBoundingBox(TERMINAL_INPUT_L, 2, 4, 14, 5, 7, 16),
            new TerminalBoundingBox(TERMINAL_INPUT_N, 11, 4, 14, 14, 7, 16),
            new TerminalBoundingBox(TERMINAL_OUTPUT_L, 2, 4, 0, 5, 7, 2),
            new TerminalBoundingBox(TERMINAL_OUTPUT_N, 11, 4, 0, 14, 7, 2),
    };

    public VoltageRelayBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(CLOSED, true));
        setTerminalCollection(horizontalNorthTerminals(this, TERMINALS_NORTH, SHAPE_NORTH));
    }

    private static Component terminal(String key, ChatFormatting color) {
        return Component.translatable(PowerSafeGrid.MOD_ID + ".terminal." + key).withStyle(color);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CLOSED);
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        var local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        ITerminalPlacement terminal = terminalAt(state, local);
        if (terminal != null)
            return InteractionResult.PASS;
        if (PSGTools.isResetTool(player.getItemInHand(hand))) {
            if (!world.isClientSide)
                withBlockEntityDo(world, pos, VoltageRelayBlockEntity::reset);
            return InteractionResult.SUCCESS;
        }
        if (!world.isClientSide)
            withBlockEntityDo(world, pos, be -> player.openMenu(be, buf -> buf.writeBlockPos(pos)));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Class<VoltageRelayBlockEntity> getBlockEntityClass() {
        return VoltageRelayBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends VoltageRelayBlockEntity> getBlockEntityType() {
        return PSGBlockEntities.VOLTAGE_RELAY.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.relay.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.relay.2").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.reset").withStyle(ChatFormatting.AQUA));
    }
}
