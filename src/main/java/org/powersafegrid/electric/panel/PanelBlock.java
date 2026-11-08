package org.powersafegrid.electric.panel;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.HorizontalElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.wire.powercord.IAcceptCord;
import org.powersafegrid.PowerSafeGrid;
import org.powersafegrid.electric.PSGBlockEntities;
import org.powersafegrid.electric.PSGItems;
import org.powersafegrid.electric.PSGTools;
import org.powersafegrid.electric.VoltageRelayBlock;

import java.util.List;

/**
 * Distribution board (panel). A row of slots holding breakers (8/10/16/63 A)
 * and voltage relay modules. Every slot has a pair of terminals: the input
 * contact on the top face and the output contact on the bottom face; the
 * installed module connects them. The voltage relay takes two adjacent slots.
 */
@MethodsReturnNonnullByDefault
public class PanelBlock extends HorizontalElectricBlock implements IBE<PanelBlockEntity>, IAcceptCord {

    public static final Component TERMINAL_IN = Component.translatable(PowerSafeGrid.MOD_ID + ".terminal.panel.in").withStyle(ChatFormatting.GOLD);
    public static final Component TERMINAL_OUT = Component.translatable(PowerSafeGrid.MOD_ID + ".terminal.panel.out").withStyle(ChatFormatting.BLUE);

    private final int slots;

    private static VoxelShape shape() {
        // Thin vertical panel flush against the back of the block cell.
        return Shapes.box(0, 0, 0, 1, 1, 0.25);
    }

    private static TerminalBoundingBox[] terminals(int slots) {
        var list = new TerminalBoundingBox[slots * 2];
        for (int i = 0; i < slots; i++) {
            double x0 = i * 16.0 / slots;
            double x1 = (i + 1) * 16.0 / slots;
            list[2 * i] = new TerminalBoundingBox(TERMINAL_IN, x0, 14, 0, x1, 16, 4);
            list[2 * i + 1] = new TerminalBoundingBox(TERMINAL_OUT, x0, 0, 0, x1, 2, 4);
        }
        return list;
    }

    public PanelBlock(Properties properties, int slots) {
        super(properties);
        this.slots = slots;
        setTerminalCollection(horizontalNorthTerminals(this, terminals(slots), shape()));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        var face = ctx.getClickedFace();
        Direction facing = face.getAxis() == Direction.Axis.Y
                ? ctx.getHorizontalDirection()
                : face;
        return defaultBlockState().setValue(HORIZONTAL_FACING, facing);
    }

    public int getSlotCount() {
        return slots;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        var held = player.getItemInHand(hand);
        ITerminalPlacement terminal = terminalAt(state, hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
        int terminalIndex = terminal != null ? terminalIndexAt(state, hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ())) : -1;
        int clickedSlot = terminalIndex >= 0 ? terminalIndex / 2 : frontSlotAt(state, pos, hit);

        if (!(world.getBlockEntity(pos) instanceof PanelBlockEntity panel))
            return InteractionResult.PASS;

        // 1. Install a module.
        if (held.is(PSGItems.BREAKER_8A.get()) || held.is(PSGItems.BREAKER_10A.get())
                || held.is(PSGItems.BREAKER_16A.get()) || held.is(PSGItems.BREAKER_63A.get())
                || held.is(PSGItems.VOLTAGE_RELAY.get())) {
            if (!world.isClientSide) {
                var module = PanelBlockEntity.moduleOfItem(held);
                int slot;
                if (module == PanelBlockEntity.PanelModule.RELAY) {
                    slot = panel.findFreePair(clickedSlot);
                    if (slot < 0) {
                        panel.message(player, "no_pair");
                        return InteractionResult.FAIL;
                    }
                } else {
                    slot = clickedSlot >= 0 && panel.isFree(clickedSlot) ? clickedSlot : panel.firstFreeSlot();
                    if (slot < 0) {
                        panel.message(player, "no_free");
                        return InteractionResult.FAIL;
                    }
                    if (!panel.isFree(slot)) {
                        panel.message(player, "occupied");
                        return InteractionResult.FAIL;
                    }
                }
                panel.install(slot, module);
                if (!player.getAbilities().instabuild)
                    held.shrink(1);
                panel.message(player, "installed", slot + 1);
            }
            return InteractionResult.SUCCESS;
        }

        // 2. Wrench: extract the module from the clicked (or first) slot.
        if (PSGTools.isResetTool(held)) {
            if (!world.isClientSide) {
                int slot = clickedSlot >= 0 ? clickedSlot : panel.firstOccupiedSlot();
                var stack = panel.extract(slot);
                if (!stack.isEmpty()) {
                    if (!player.getInventory().add(stack))
                        world.addFreshEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
                } else {
                    panel.message(player, "nothing");
                }
            }
            return InteractionResult.SUCCESS;
        }

        // 3. Empty hand: operate the breaker lever / reset the relay.
        if (held.isEmpty() && clickedSlot >= 0) {
            if (!world.isClientSide)
                panel.operate(clickedSlot, player);
            return InteractionResult.SUCCESS;
        }

        // Terminal clicks with anything else fall through for wire attachment.
        if (terminal != null)
            return InteractionResult.PASS;
        return InteractionResult.PASS;
    }

    /** Slot index under the cursor when the panel's front face is clicked. */
    private int frontSlotAt(BlockState state, BlockPos pos, BlockHitResult hit) {
        var local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        var facing = state.getValue(HORIZONTAL_FACING);
        boolean onFront = switch (facing) {
            case NORTH -> local.z < 0.3;
            case SOUTH -> local.z > 0.7;
            case WEST -> local.x < 0.3;
            case EAST -> local.x > 0.7;
            default -> false;
        };
        if (!onFront || local.y < 0.15 || local.y > 0.95)
            return -1;
        double along = switch (facing) {
            case NORTH, EAST -> local.x;
            default -> local.z;
        };
        int slot = Mth.clamp((int) (along * slots), 0, slots - 1);
        if (facing == Direction.SOUTH || facing == Direction.WEST)
            slot = slots - 1 - slot;
        return slot;
    }

    @Override
    public Class<PanelBlockEntity> getBlockEntityClass() {
        return PanelBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PanelBlockEntity> getBlockEntityType() {
        return PSGBlockEntities.PANEL.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.panel.1", slots).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.panel.2").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.panel.3").withStyle(ChatFormatting.DARK_GRAY));
    }
}
