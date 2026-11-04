package org.powersafegrid.electric;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.collections.ModdedSoundEvents;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.powersafegrid.PowerSafeGrid;
import org.powersafegrid.gui.VoltageRelayMenu;

import java.util.List;

public class VoltageRelayBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation, MenuProvider {
    public static final float RESISTANCE = 0.05f;
    public static final float OVERLOAD_CURRENT = 60f;
    public static final float EXPLODE_CURRENT = 100f;
    public static final int OVERLOAD_CONFIRM_TICKS = 5;
    public static final int EXPLODE_CONFIRM_TICKS = 10;
    public static final int RECONNECT_DELAY = 60;
    public static final float MIN_DISPLAY_VOLTAGE = 320f;

    public enum Status {
        CLOSED,   // обычное
        OPEN,     // разорвано (напряжение вне диапазона)
        OVERLOAD; // перегруз (требует сброса инструментом)

        public static final Status[] VALUES = values();
    }

    private SwitchedWire wire1;
    private SwitchedWire wire2;

    private float lowLimit = 90f;
    private float highLimit = 130f;
    private Status status = Status.CLOSED;

    private int overloadTimer;
    private int reconnectTimer;
    private boolean playEffects;

    public VoltageRelayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electricBehaviour.reducedSync();
    }

    public float getLowLimit() {
        return lowLimit;
    }

    public float getHighLimit() {
        return highLimit;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isTripped() {
        return status == Status.OVERLOAD;
    }

    /** Voltage across the input terminals, valid on both sides. */
    public double getInputVoltage() {
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(1))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(0).getVoltage() - electricBehaviour.getTerminal(1).getVoltage());
    }

    /** Current through the relay, computed from the (synced) network state. */
    public double getRelayCurrent() {
        if (wire1 == null || status == Status.OVERLOAD)
            return 0;
        return Math.abs(wire1.current());
    }

    public void setRange(float low, float high) {
        low = net.minecraft.util.Mth.clamp(low, 0f, MIN_DISPLAY_VOLTAGE - 10f);
        high = net.minecraft.util.Mth.clamp(high, low + 10f, MIN_DISPLAY_VOLTAGE);
        if (low == lowLimit && high == highLimit)
            return;
        this.lowLimit = low;
        this.highLimit = high;
        reconnectTimer = 0;
        setChanged();
        notifyUpdate();
    }

    /** Client-side preview of the limits while the GUI slider is being dragged. */
    public void setRangeClient(float low, float high) {
        this.lowLimit = low;
        this.highLimit = high;
    }

    /** Reconnect the relay. Called on server side only. */
    public void setClosed() {
        status = Status.CLOSED;
        if (wire1 != null && wire2 != null) {
            wire1.setState(true);
            wire2.setState(true);
        }
        overloadTimer = 0;
        reconnectTimer = 0;
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(VoltageRelayBlock.CLOSED, true));
        ModdedSoundEvents.RELAY_CLICK.playOnServer(level, worldPosition, 0.6f, 1.2f);
        setChanged();
        notifyUpdate();
    }

    /** Break the circuit because the voltage left the configured window. */
    private void setOpen() {
        status = Status.OPEN;
        if (wire1 != null && wire2 != null) {
            wire1.setState(false);
            wire2.setState(false);
        }
        reconnectTimer = 0;
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(VoltageRelayBlock.CLOSED, false));
        ModdedSoundEvents.RELAY_CLICK.playOnServer(level, worldPosition, 0.6f, 0.8f);
        setChanged();
        notifyUpdate();
    }

    /** Overcurrent above {@link #OVERLOAD_CURRENT}: smoke, then latch off. */
    private void tripOverload() {
        status = Status.OVERLOAD;
        if (wire1 != null && wire2 != null) {
            wire1.setState(false);
            wire2.setState(false);
        }
        overloadTimer = 0;
        reconnectTimer = 0;
        playEffects = true;
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(VoltageRelayBlock.CLOSED, false));
        ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 1f, 0.8f);
        setChanged();
        notifyUpdate();
    }

    /** Overcurrent above {@link #EXPLODE_CURRENT}: the device explodes. */
    private void explode() {
        ThermalBehaviour.explode(level, worldPosition, getBlockState(), 4f);
    }

    /** Screwdriver / wrench reset. */
    public void reset() {
        if (!isTripped())
            return;
        setClosed();
    }

    @Override
    public void electricalTick() {
        applyPower(wire1);
        if (level.isClientSide || wire1 == null || wire2 == null)
            return;
        if (!wire1.isConverged())
            return;
        if (status == Status.OVERLOAD)
            return;

        double u = getInputVoltage();
        double i = getRelayCurrent();

        // Overcurrent handling, ordered from most to least severe.
        if (i >= EXPLODE_CURRENT) {
            if (++overloadTimer >= EXPLODE_CONFIRM_TICKS)
                explode();
            return;
        }
        if (i > OVERLOAD_CURRENT) {
            if (++overloadTimer >= OVERLOAD_CONFIRM_TICKS)
                tripOverload();
            return;
        }
        overloadTimer = 0;

        if (status == Status.CLOSED) {
            if (u > 1 && (u < lowLimit || u > highLimit))
                setOpen();
        } else if (status == Status.OPEN) {
            if (u > 1 && u >= lowLimit && u <= highLimit) {
                if (++reconnectTimer >= RECONNECT_DELAY)
                    setClosed();
            } else {
                reconnectTimer = 0;
            }
        }
    }

    public void spawnTripEffects() {
        if (level == null || !level.isClientSide)
            return;
        var center = worldPosition.getCenter();
        for (int j = 0; j < 12; ++j) {
            double dx = level.random.nextGaussian() * 0.1;
            double dy = level.random.nextDouble() * 0.3;
            double dz = level.random.nextGaussian() * 0.1;
            level.addParticle(j % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    center.x + dx, center.y + 0.4 + dy, center.z + dz, 0, 0.02, 0);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        lowLimit = tag.getFloat("LowLimit");
        highLimit = tag.getFloat("HighLimit");
        status = Status.VALUES[tag.getByte("Status") % Status.VALUES.length];
        if (clientPacket && tag.getBoolean("Effect")) {
            playEffects = false;
            spawnTripEffects();
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("LowLimit", lowLimit);
        tag.putFloat("HighLimit", highLimit);
        tag.putByte("Status", (byte) status.ordinal());
        if (playEffects) {
            tag.putBoolean("Effect", true);
            playEffects = false;
        }
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(4);
        boolean closed = status == Status.CLOSED;
        wire1 = builder.connectSwitch(RESISTANCE, builder.terminalNode(0), builder.terminalNode(2), closed);
        wire2 = builder.connectSwitch(RESISTANCE, builder.terminalNode(1), builder.terminalNode(3), closed);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.translatable(key("status_header")).withStyle(ChatFormatting.GOLD));
        ChatFormatting statusColor = switch (status) {
            case CLOSED -> ChatFormatting.GREEN;
            case OPEN -> ChatFormatting.YELLOW;
            case OVERLOAD -> ChatFormatting.RED;
        };
        tooltip.add(Component.translatable(key("status_" + status.name().toLowerCase())).withStyle(statusColor));
        tooltip.add(line(key("voltage"), String.format("%.0f V", getInputVoltage())));
        tooltip.add(line(key("current"), String.format("%.1f A", getRelayCurrent())));
        tooltip.add(line(key("range"), String.format("%.0f - %.0f V", lowLimit, highLimit)));
        if (status == Status.OVERLOAD)
            tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".goggles.reset_hint").withStyle(ChatFormatting.AQUA));
        return true;
    }

    private static Component line(String translationKey, String value) {
        return Component.translatable(translationKey)
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }

    private String key(String suffix) {
        return PowerSafeGrid.MOD_ID + ".goggles.relay." + suffix;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new VoltageRelayMenu(id, inventory, this);
    }
}
