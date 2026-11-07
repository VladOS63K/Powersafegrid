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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.HorizontalElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireConnection;
import org.patryk3211.powergrid.electricity.wire.powercord.IAcceptCord;
import org.patryk3211.powergrid.collections.ModdedDataComponents;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

/**
 * Adjustable autotransformer rendered as a 2x2x2 machine. Occupies a single
 * block cell; the model extends towards +X/+Y/+Z for visuals only. Converts
 * the input voltage (up to the rated maximum) to 0-100 % of it, selected with
 * a scroll value box. Protection mirrors the stabilizer: moderate overvoltage
 * latches the device off, severe overvoltage (3x the rated maximum) burns it
 * up, overcurrent smokes and latches off / explodes.
 */
@MethodsReturnNonnullByDefault
public class InverterBlock extends HorizontalElectricBlock implements IBE<InverterBlockEntity>, IAcceptCord {

    public static final BooleanProperty TRIPPED = BooleanProperty.create("tripped");

    public static final Component TERMINAL_INPUT_L = VoltageRelayBlock.TERMINAL_INPUT_L;
    public static final Component TERMINAL_INPUT_N = VoltageRelayBlock.TERMINAL_INPUT_N;
    public static final Component TERMINAL_OUTPUT_L = VoltageRelayBlock.TERMINAL_OUTPUT_L;
    public static final Component TERMINAL_OUTPUT_N = VoltageRelayBlock.TERMINAL_OUTPUT_N;

    /** Terminals of the big variant: on the faces of the 2x2x2 visual cube. */
    private static final TerminalBoundingBox[] BIG_TERMINALS_NORTH = new TerminalBoundingBox[] {
            new TerminalBoundingBox(TERMINAL_INPUT_L, 2, 5, -2, 5, 8, 2),
            new TerminalBoundingBox(TERMINAL_INPUT_N, 11, 5, -2, 14, 8, 2),
            new TerminalBoundingBox(TERMINAL_OUTPUT_L, 2, 5, 30, 5, 8, 34),
            new TerminalBoundingBox(TERMINAL_OUTPUT_N, 11, 5, 30, 14, 8, 34),
    };

    /** Terminals of the compact variant: on its own back and front faces. */
    private static final TerminalBoundingBox[] COMPACT_TERMINALS_NORTH = new TerminalBoundingBox[] {
            new TerminalBoundingBox(TERMINAL_INPUT_L, 2, 4, 14, 5, 7, 16),
            new TerminalBoundingBox(TERMINAL_INPUT_N, 11, 4, 14, 14, 7, 16),
            new TerminalBoundingBox(TERMINAL_OUTPUT_L, 2, 4, 0, 5, 7, 2),
            new TerminalBoundingBox(TERMINAL_OUTPUT_N, 11, 4, 0, 14, 7, 2),
    };

    private static final VoxelShape COMPACT_SHAPE_NORTH = Shapes.or(
            Shapes.box(1 / 16.0, 0, 2 / 16.0, 15 / 16.0, 14 / 16.0, 14 / 16.0),
            Shapes.box(2 / 16.0, 4 / 16.0, 14 / 16.0, 5 / 16.0, 7 / 16.0, 1),
            Shapes.box(11 / 16.0, 4 / 16.0, 14 / 16.0, 14 / 16.0, 7 / 16.0, 1),
            Shapes.box(2 / 16.0, 4 / 16.0, 0, 5 / 16.0, 7 / 16.0, 2 / 16.0),
            Shapes.box(11 / 16.0, 4 / 16.0, 0, 14 / 16.0, 7 / 16.0, 2 / 16.0));

    private final boolean big;
    private final float maxInput;
    private final float overloadCurrent;
    private final float explodeCurrent;

    public InverterBlock(Properties properties, boolean big, float maxInput, float overloadCurrent, float explodeCurrent) {
        super(properties);
        this.big = big;
        this.maxInput = maxInput;
        this.overloadCurrent = overloadCurrent;
        this.explodeCurrent = explodeCurrent;
        if (big) {
            setTerminalCollection(horizontalNorthTerminals(this, BIG_TERMINALS_NORTH, Shapes.block()));
        } else {
            setTerminalCollection(horizontalNorthTerminals(this, COMPACT_TERMINALS_NORTH, COMPACT_SHAPE_NORTH));
        }
    }

    public float getMaxInput() {
        return maxInput;
    }

    public float getOverloadCurrent() {
        return overloadCurrent;
    }

    public float getExplodeCurrent() {
        return explodeCurrent;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TRIPPED);
    }

    @Override
    public InteractionResult onWire(BlockState state, UseOnContext context) {
        return onWireAt(state, context);
    }

    /**
     * Faithful copy of {@code IElectric.onWire} (the interface is only inherited
     * indirectly here, so {@code IElectric.super} is not available).
     */
    private InteractionResult onWireAt(BlockState state, UseOnContext context) {
        var stack = context.getItemInHand();
        var pos = context.getClickedPos();
        var terminal = terminalIndexAt(state, context.getClickLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
        if (terminal < 0)
            return InteractionResult.PASS;
        if (!accepts(context.getItemInHand())) {
            feedback(context, Component.translatable("powergrid.message.connection_incorrect_wire_type").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        if (stack.has(ModdedDataComponents.CONNECTION_DATA.get())) {
            // Continuing a connection.
            var endpoint = stack.get(ModdedDataComponents.CONNECTION_DATA.get()).endpoint();
            if (endpoint == null)
                return InteractionResult.FAIL;
            var result = IElectric.makeConnection(context.getLevel(), endpoint, new BlockWireEndpoint(pos, terminal), context);
            if (result.consumesAction())
                stack.remove(ModdedDataComponents.CONNECTION_DATA.get());
            return result;
        }
        // Must be the first connection.
        var endpoint = new BlockWireEndpoint(pos, terminal);
        stack.set(ModdedDataComponents.CONNECTION_DATA.get(), WireConnection.of(endpoint));
        feedback(context, Component.translatable("powergrid.message.connection_next").withStyle(ChatFormatting.GRAY));
        return InteractionResult.SUCCESS;
    }

    private static void feedback(UseOnContext context, Component text) {
        if (context.getPlayer() != null)
            context.getPlayer().displayClientMessage(text, true);
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ITerminalPlacement terminal = terminalAt(state, hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
        if (terminal != null)
            return InteractionResult.PASS;
        if (!PSGTools.isResetTool(player.getItemInHand(hand)))
            return InteractionResult.PASS;
        if (!world.isClientSide && world.getBlockEntity(pos) instanceof InverterBlockEntity inverter && inverter.isTripped())
            inverter.reset();
        return InteractionResult.SUCCESS;
    }

    public boolean isBig() {
        return big;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return big ? Shapes.block() : super.getShape(state, level, pos, context);
    }

    @Override
    public Class<InverterBlockEntity> getBlockEntityClass() {
        return InverterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends InverterBlockEntity> getBlockEntityType() {
        return PSGBlockEntities.INVERTER.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.inverter.1",
                String.format("%.0f", maxInput)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.inverter.4").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.inverter.3",
                String.format("%.0f", overloadCurrent), String.format("%.0f", explodeCurrent)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.reset").withStyle(ChatFormatting.AQUA));
    }
}
