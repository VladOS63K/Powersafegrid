package org.powersafegrid.electric;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.collections.ModdedDataComponents;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.HorizontalElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireConnection;
import org.patryk3211.powergrid.electricity.wire.powercord.AutoCordEndpoint;
import org.patryk3211.powergrid.electricity.wire.powercord.IAcceptCord;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

/**
 * Adjustable autotransformer. Two variants share this block class: a compact
 * single-block device and a big 2x2x2 multiblock. The multiblock forms
 * automatically once eight blocks make up a full cube; the lowest corner
 * becomes the controller that owns the electric circuit, and wire terminals
 * stick out of the assembled cube's front and back faces.
 */
@MethodsReturnNonnullByDefault
public class InverterBlock extends HorizontalElectricBlock implements IBE<InverterBlockEntity>, IAcceptCord {

    public static final BooleanProperty CONTROLLER = BooleanProperty.create("controller");
    public static final BooleanProperty TRIPPED = BooleanProperty.create("tripped");

    public static final Component TERMINAL_INPUT_L = VoltageRelayBlock.TERMINAL_INPUT_L;
    public static final Component TERMINAL_INPUT_N = VoltageRelayBlock.TERMINAL_INPUT_N;
    public static final Component TERMINAL_OUTPUT_L = VoltageRelayBlock.TERMINAL_OUTPUT_L;
    public static final Component TERMINAL_OUTPUT_N = VoltageRelayBlock.TERMINAL_OUTPUT_N;

    /** Terminals of an assembled 2x2x2 multiblock: input on the back face, output on the front face. */
    private static final TerminalBoundingBox[] MULTIBLOCK_TERMINALS_NORTH = new TerminalBoundingBox[] {
            new TerminalBoundingBox(TERMINAL_INPUT_L, 2, 5, -2, 5, 8, 2),
            new TerminalBoundingBox(TERMINAL_INPUT_N, 11, 5, -2, 14, 8, 2),
            new TerminalBoundingBox(TERMINAL_OUTPUT_L, 2, 5, 30, 5, 8, 34),
            new TerminalBoundingBox(TERMINAL_OUTPUT_N, 11, 5, 30, 14, 8, 34),
    };

    /** Terminals of the compact single-block variant. */
    private static final TerminalBoundingBox[] SINGLE_TERMINALS_NORTH = new TerminalBoundingBox[] {
            new TerminalBoundingBox(TERMINAL_INPUT_L, 2, 4, 14, 5, 7, 16),
            new TerminalBoundingBox(TERMINAL_INPUT_N, 11, 4, 14, 14, 7, 16),
            new TerminalBoundingBox(TERMINAL_OUTPUT_L, 2, 4, 0, 5, 7, 2),
            new TerminalBoundingBox(TERMINAL_OUTPUT_N, 11, 4, 0, 14, 7, 2),
    };

    private static final VoxelShape SINGLE_SHAPE_NORTH = Shapes.or(
            Shapes.box(1 / 16.0, 0, 2 / 16.0, 15 / 16.0, 14 / 16.0, 14 / 16.0),
            Shapes.box(2 / 16.0, 4 / 16.0, 14 / 16.0, 5 / 16.0, 7 / 16.0, 1),
            Shapes.box(11 / 16.0, 4 / 16.0, 14 / 16.0, 14 / 16.0, 7 / 16.0, 1),
            Shapes.box(2 / 16.0, 4 / 16.0, 0, 5 / 16.0, 7 / 16.0, 2 / 16.0),
            Shapes.box(11 / 16.0, 4 / 16.0, 0, 14 / 16.0, 7 / 16.0, 2 / 16.0));

    public InverterBlock(Properties properties, boolean multiblock, float maxInput, float overloadCurrent, float explodeCurrent) {
        super(properties);
        this.multiblock = multiblock;
        this.maxInput = maxInput;
        this.overloadCurrent = overloadCurrent;
        this.explodeCurrent = explodeCurrent;
        if (multiblock) {
            setTerminalCollection(horizontalNorthTerminals(this, MULTIBLOCK_TERMINALS_NORTH, Shapes.block()));
        } else {
            registerDefaultState(defaultBlockState().setValue(CONTROLLER, true));
            setTerminalCollection(horizontalNorthTerminals(this, SINGLE_TERMINALS_NORTH, SINGLE_SHAPE_NORTH));
        }
    }

    private final boolean multiblock;
    private final float maxInput;
    private final float overloadCurrent;
    private final float explodeCurrent;

    public boolean isMultiblock() {
        return multiblock;
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

    public boolean isController(BlockState state) {
        return !multiblock || state.getValue(CONTROLLER);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        // CONTROLLER must be added unconditionally: this method runs from the
        // super constructor, before the multiblock field is assigned.
        builder.add(CONTROLLER);
        builder.add(TRIPPED);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // Auto-build the 2x2x2 cube from a single placement: the placed block
        // becomes the controller and the remaining 7 positions are filled with
        // shells (when there is room).
        if (!multiblock || level.isClientSide || !(level instanceof ServerLevel server))
            return;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    if (x == 0 && y == 0 && z == 0)
                        continue;
                    if (!level.getBlockState(pos.offset(x, y, z)).canBeReplaced()) {
                        PowerSafeGrid.LOGGER.info("Inverter at {} could not auto-build: no room at +X/+Y/+Z",
                                pos.toShortString());
                        return;
                    }
                }
            }
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    if (x == 0 && y == 0 && z == 0)
                        continue;
                    level.setBlock(pos.offset(x, y, z), state.setValue(CONTROLLER, false), 3);
                }
            }
        }
        level.setBlock(pos, state.setValue(CONTROLLER, true), 3);
        if (level.getBlockEntity(pos) instanceof InverterBlockEntity be)
            be.onControllerChanged();
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (multiblock && !level.isClientSide && !oldState.is(this))
            level.scheduleTick(pos, this, 1);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (multiblock && !level.isClientSide)
            level.scheduleTick(pos, this, 1);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (multiblock && state.is(this) && !newState.is(this) && level instanceof ServerLevel server)
            updateFormation(server, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        updateFormation(level, pos);
    }

    /**
     * Re-evaluates multiblock formation around {@code pos}. A cube is complete
     * when all 8 positions of a 2x2x2 section hold multiblock converter blocks
     * and none of them belongs to another complete cube. The lowest corner
     * becomes the controller.
     */
    public void updateFormation(ServerLevel level, BlockPos pos) {
        BlockPos anchor = findCubeAnchor(level, pos);
        BlockPos min = pos.subtract(new BlockPos(1, 1, 1));
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    BlockPos p = min.offset(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!(s.getBlock() instanceof InverterBlock block) || !block.multiblock)
                        continue;
                    boolean shouldBeController = anchor != null && p.equals(anchor);
                    if (s.getValue(CONTROLLER) != shouldBeController) {
                        level.setBlock(p, s.setValue(CONTROLLER, shouldBeController), 3);
                        if (level.getBlockEntity(p) instanceof InverterBlockEntity be)
                            be.onControllerChanged();
                    }
                }
            }
        }
    }

    /**
     * @return the lowest corner of a complete 2x2x2 converter cube containing
     * {@code pos}, or null if none is complete.
     */
    public BlockPos findCubeAnchor(ServerLevel level, BlockPos pos) {
        for (int dx = 1; dx >= 0; dx--) {
            for (int dy = 1; dy >= 0; dy--) {
                for (int dz = 1; dz >= 0; dz--) {
                    BlockPos anchor = pos.subtract(new BlockPos(dx, dy, dz));
                    if (isCompleteCube(level, anchor))
                        return anchor;
                }
            }
        }
        return null;
    }

    private boolean isCompleteCube(ServerLevel level, BlockPos anchor) {
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos p = anchor.offset(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!(s.getBlock() instanceof InverterBlock block) || !block.multiblock)
                        return false;
                    // A block that is the controller of another cube section cannot join this one.
                    if (s.getValue(CONTROLLER) && !p.equals(anchor))
                        return false;
                }
            }
        }
        // Do not allow cubes to share blocks with other complete cubes.
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos p = anchor.offset(x, y, z);
                    for (int dx = 1; dx >= 0; dx--) {
                        for (int dy = 1; dy >= 0; dy--) {
                            for (int dz = 1; dz >= 0; dz--) {
                                BlockPos other = p.subtract(new BlockPos(dx, dy, dz));
                                if (!other.equals(anchor) && isPlainCompleteCube(level, other))
                                    return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean isPlainCompleteCube(ServerLevel level, BlockPos anchor) {
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockState s = level.getBlockState(anchor.offset(x, y, z));
                    if (!(s.getBlock() instanceof InverterBlock block) || !block.multiblock)
                        return false;
                }
            }
        }
        return true;
    }

    @Override
    public InteractionResult onWire(BlockState state, UseOnContext context) {
        // Forward terminal clicks on multiblock shells to the controller.
        if (multiblock) {
            var level = context.getLevel();
            var clicked = context.getClickedPos();
            BlockPos controllerPos = null;
            if (level instanceof ServerLevel server)
                controllerPos = findCubeAnchor(server, clicked);
            if (controllerPos != null && !controllerPos.equals(clicked)) {
                var hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), controllerPos, false);
                var remapped = new UseOnContext(level, context.getPlayer(), context.getHand(), context.getItemInHand(), hit);
                var controllerState = level.getBlockState(controllerPos);
                if (controllerState.getBlock() instanceof InverterBlock)
                    return onWireAt(controllerState, remapped);
                return InteractionResult.PASS;
            }
        }
        return onWireAt(state, context);
    }

    /**
     * Faithful copy of {@code IElectric.onWire}, with the wire endpoint bound
     * to the block position of the passed-in state (the multiblock controller
     * for shell clicks).
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
    public AutoCordEndpoint getEndpoint(UseOnContext context) {
        // Power cords plug into the assembled cube's back face and must be
        // bound to the controller's terminals, not to a shell block.
        if (multiblock) {
            var level = context.getLevel();
            var clicked = context.getClickedPos();
            BlockPos controllerPos = null;
            if (level instanceof ServerLevel server)
                controllerPos = findCubeAnchor(server, clicked);
            if (controllerPos != null && !controllerPos.equals(clicked)) {
                var loc = context.getClickLocation().relative(context.getClickedFace(), 3 / 16f);
                return new AutoCordEndpoint(controllerPos, 0, 1, loc, context.getClickedFace());
            }
        }
        return IAcceptCord.super.getEndpoint(context);
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ITerminalPlacement terminal = terminalAt(state, hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
        if (terminal != null)
            return InteractionResult.PASS;
        if (!PSGTools.isResetTool(player.getItemInHand(hand)))
            return InteractionResult.PASS;
        if (!world.isClientSide) {
            BlockPos target = pos;
            if (multiblock && world instanceof ServerLevel server) {
                var controllerPos = findCubeAnchor(server, pos);
                if (controllerPos != null)
                    target = controllerPos;
            }
            if (world.getBlockEntity(target) instanceof InverterBlockEntity inverter && inverter.isTripped())
                inverter.reset();
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return multiblock ? Shapes.block() : super.getShape(state, level, pos, context);
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
        if (multiblock)
            tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.inverter.2").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.inverter.3",
                String.format("%.0f", overloadCurrent), String.format("%.0f", explodeCurrent)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.reset").withStyle(ChatFormatting.AQUA));
    }
}
