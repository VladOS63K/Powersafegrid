package org.powersafegrid.electric;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.collections.ModdedSoundEvents;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.TransformerCoupling;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

/**
 * Variac-style voltage stabilizer. The output voltage is held at the block's
 * target voltage by continuously adjusting a transformer coupling ratio while
 * the input voltage stays inside the working window. Close to the lower edge
 * of the window the boost capability fades out and the output starts to sag
 * and flicker. Leaving the window latches the device off; a severe overvoltage
 * or overcurrent makes it smoke and finally explode. A tripped stabilizer only
 * recovers when it is reset with a screwdriver/wrench.
 */
public class StabilizerBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {
    public static final float PRIMARY_RESISTANCE = 0.1f;
    public static final float SECONDARY_RESISTANCE = 0.1f;
    public static final float COUPLING_RESISTANCE = 0.5f;
    public static final float OVERLOAD_CURRENT = 60f;
    public static final float EXPLODE_CURRENT = 100f;
    /** Above this multiple of the upper window limit an overvoltage burns the stabilizer up. */
    public static final float BURN_VOLTAGE_MULTIPLIER = 3f;
    public static final int OVERLOAD_CONFIRM_TICKS = 5;
    public static final int EXPLODE_CONFIRM_TICKS = 10;
    public static final int BURN_TICKS = 40;
    public static final float RATIO_SLEW = 0.08f;
    public static final float MIN_RATIO = 0.1f;
    public static final float MAX_RATIO = 4f;
    /** Fraction of the working window above the lower limit where regulation is still full. */
    public static final float REGULATION_HOLD_FRACTION = 0.25f;
    public static final float FLICKER_AMPLITUDE = 0.06f;
    /** Extra boost headroom above the ideal ratio to cover load losses. */
    public static final float LOAD_MARGIN = 1.25f;

    public enum Status {
        RUNNING,   // норма
        OVERLOAD,  // перегруз
        VOLTAGE;   // напряжение вне рабочего диапазона

        public static final Status[] VALUES = values();
    }

    private final float targetVoltage;
    private final float minVoltage;
    private final float maxVoltage;

    private SwitchedWire p1w;
    private SwitchedWire p2w;
    private SwitchedWire s1w;
    private SwitchedWire s2w;
    private TransformerCoupling coupling;

    private Status status = Status.RUNNING;
    private float ratio = 1f;
    private int overloadTimer;
    private int voltTimer;
    private int burnTimer = -1;
    private boolean primed;
    private boolean playEffects;

    public StabilizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        if (state.getBlock() instanceof StabilizerBlock block) {
            targetVoltage = block.getTargetVoltage();
            minVoltage = block.getMinVoltage();
            maxVoltage = block.getMaxVoltage();
        } else {
            targetVoltage = 120f;
            minVoltage = 90f;
            maxVoltage = 130f;
        }
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electricBehaviour.reducedSync();
    }

    public Status getStatus() {
        return burnTimer >= 0 ? Status.OVERLOAD : status;
    }

    public boolean isTripped() {
        return status != Status.RUNNING || burnTimer >= 0;
    }

    public boolean isBurning() {
        return burnTimer >= 0;
    }

    public double getInputVoltage() {
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(1))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(0).getVoltage() - electricBehaviour.getTerminal(1).getVoltage());
    }

    /** Voltage across the output terminals. */
    public double getOutputVoltage() {
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(3))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(2).getVoltage() - electricBehaviour.getTerminal(3).getVoltage());
    }

    /**
     * The highest voltage present on either side of the device. Protection
     * reacts to this, so an overvoltage source is caught no matter which side
     * it is fed from (or if the other side is left open).
     */
    public double monitoredVoltage() {
        return Math.max(getInputVoltage(), getOutputVoltage());
    }

    public double getOutputCurrent() {
        if (s1w == null || status != Status.RUNNING || burnTimer >= 0)
            return 0;
        return Math.abs(s1w.current());
    }

    public float getTargetVoltage() {
        return targetVoltage;
    }

    private void applyWireStates(boolean closed) {
        if (p1w != null) {
            p1w.setState(false);
            p1w.setState(closed);
        }
        if (p2w != null) {
            p2w.setState(false);
            p2w.setState(closed);
        }
        if (s1w != null) {
            s1w.setState(false);
            s1w.setState(closed);
        }
        if (s2w != null) {
            s2w.setState(false);
            s2w.setState(closed);
        }
    }

    private void trip(Status newStatus) {
        if (status == newStatus && burnTimer < 0)
            return;
        status = newStatus;
        applyWireStates(false);
        overloadTimer = 0;
        playEffects = true;
        PowerSafeGrid.LOGGER.info("Stabilizer at {} tripped ({}), input {} V, output current {} A",
                worldPosition.toShortString(), newStatus, String.format("%.0f", getInputVoltage()),
                String.format("%.1f", getOutputCurrent()));
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(StabilizerBlock.TRIPPED, true));
        ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 1f, 0.8f);
        setChanged();
        notifyUpdate();
    }

    /** Severe overvoltage: smoke for a moment, then explode. */
    private void startBurn() {
        if (burnTimer >= 0)
            return;
        status = Status.VOLTAGE;
        applyWireStates(false);
        burnTimer = BURN_TICKS + level.random.nextInt(20);
        playEffects = true;
        PowerSafeGrid.LOGGER.warn("Stabilizer at {} is burning up, monitored voltage {} V",
                worldPosition.toShortString(), String.format("%.0f", monitoredVoltage()));
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(StabilizerBlock.TRIPPED, true));
        ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 1.2f, 0.6f);
        setChanged();
        notifyUpdate();
    }

    private void explode() {
        ThermalBehaviour.explode(level, worldPosition, getBlockState(), 4f);
    }

    /** Screwdriver / wrench reset. */
    public void reset() {
        if (!isTripped())
            return;
        burnTimer = -1;
        status = Status.RUNNING;
        applyWireStates(true);
        ratio = 1f;
        if (coupling != null)
            coupling.setRatio(ratio);
        overloadTimer = 0;
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(StabilizerBlock.TRIPPED, false));
        ModdedSoundEvents.CONTACTOR_ON.playOnServer(level, worldPosition, 0.8f, 1f);
        setChanged();
        notifyUpdate();
    }

    @Override
    public void unpaused() {
        // Make sure freshly built internal wires are actually registered in the
        // network matrix; a device that never re-registers them passes nothing.
        electricBehaviour.rebuildCircuit(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && level.isClientSide && burnTimer >= 0 && level.random.nextInt(3) == 0) {
            var center = worldPosition.getCenter();
            level.addParticle(ParticleTypes.SMOKE, center.x, center.y + 0.5, center.z, 0, 0.02, 0);
        }
    }

    @Override
    public void electricalTick() {
        applyPower(p1w);
        applyPower(s1w);
        if (level.isClientSide || p1w == null || coupling == null)
            return;

        // One-time re-registration of the switched wires once they are in a network.
        if (!primed && p1w.getNetwork() != null) {
            primed = true;
            applyWireStates(burnTimer < 0 && status == Status.RUNNING);
        }

        // Burning countdown; the stabilizer is done for.
        if (burnTimer >= 0) {
            if (--burnTimer <= 0)
                explode();
            return;
        }

        if (status != Status.RUNNING) {
            // Even a tripped (latched off) stabilizer is destroyed when the
            // overvoltage gets severe enough - insulation breaks down.
            if (monitoredVoltage() > BURN_VOLTAGE_MULTIPLIER * maxVoltage)
                startBurn();
            return;
        }

        double uIn = getInputVoltage();
        double iOut = getOutputCurrent();

        float burnLimit = BURN_VOLTAGE_MULTIPLIER * maxVoltage;
        if (monitoredVoltage() > burnLimit) {
            startBurn();
            return;
        }

        if (iOut >= EXPLODE_CURRENT) {
            if (++overloadTimer >= EXPLODE_CONFIRM_TICKS)
                explode();
            return;
        }
        if (iOut > OVERLOAD_CURRENT) {
            if (++overloadTimer >= OVERLOAD_CONFIRM_TICKS)
                trip(Status.OVERLOAD);
            return;
        }
        overloadTimer = 0;

        // Overvoltage is checked on both sides, undervoltage on the input only
        // (the regulated output legitimately sags near the lower window edge).
        // Both use a short confirmation window to ride out transients.
        boolean over = monitoredVoltage() > maxVoltage;
        boolean under = uIn > 5 && uIn < minVoltage;
        if (over || under) {
            if (++voltTimer >= OVERLOAD_CONFIRM_TICKS)
                trip(Status.VOLTAGE);
            return;
        }
        voltTimer = 0;

        // Regulate the output by adjusting the coupling ratio. The feedback loop
        // watches the actual output voltage, compensating both input variation
        // and internal losses. Close to the lower edge of the window the boost
        // capability fades out and the output sags and flickers instead of being
        // held perfectly.
        if (uIn > 5 && p1w.isConverged()) {
            float hold = minVoltage + REGULATION_HOLD_FRACTION * (maxVoltage - minVoltage);
            float capability = uIn >= hold ? 1f
                    : Mth.clamp((float) ((uIn - minVoltage) / (hold - minVoltage)), 0f, 1f);
            float maxBoost = targetVoltage / minVoltage * LOAD_MARGIN - 1f;
            float allowed = 1f + maxBoost * capability;
            if (capability < 1f) {
                float flicker = (level.random.nextFloat() - 0.5f) * 2f * FLICKER_AMPLITUDE * (1f - capability);
                allowed *= 1f + flicker;
            }
            allowed = Mth.clamp(allowed, MIN_RATIO, MAX_RATIO);

            double uOut = Math.abs(electricBehaviour.getTerminal(2).getVoltage() - electricBehaviour.getTerminal(3).getVoltage());
            float targetRatio = ratio;
            if (uOut > 1) {
                // Step the ratio towards the value that puts the output on target.
                float correction = Mth.clamp((float) (targetVoltage / uOut), 0.9f, 1.1f);
                targetRatio = ratio * correction;
            } else if (uIn > 5) {
                // No feedback signal yet: aim for the open-circuit target.
                targetRatio = (float) (targetVoltage / uIn);
            }
            targetRatio = Math.min(targetRatio, allowed);

            float slew = 1f + RATIO_SLEW;
            float next = targetRatio > ratio
                    ? Math.min(targetRatio, ratio * slew)
                    : Math.max(targetRatio, ratio / slew);
            if (Math.abs(next - ratio) > 1e-4f) {
                ratio = next;
                coupling.setRatio(ratio);
            }
        }
    }

    public void spawnTripEffects() {
        if (level == null || !level.isClientSide)
            return;
        var center = worldPosition.getCenter();
        for (int j = 0; j < 14; ++j) {
            double dx = level.random.nextGaussian() * 0.12;
            double dy = level.random.nextDouble() * 0.4;
            double dz = level.random.nextGaussian() * 0.12;
            level.addParticle(j % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    center.x + dx, center.y + 0.5 + dy, center.z + dz, 0, 0.02, 0);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        status = Status.VALUES[tag.getByte("Status") % Status.VALUES.length];
        ratio = tag.contains("Ratio") ? tag.getFloat("Ratio") : 1f;
        burnTimer = tag.contains("Burning") ? BURN_TICKS : -1;
        if (clientPacket && tag.getBoolean("Effect")) {
            playEffects = false;
            spawnTripEffects();
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putByte("Status", (byte) status.ordinal());
        tag.putFloat("Ratio", ratio);
        if (burnTimer >= 0)
            tag.putBoolean("Burning", true);
        if (playEffects) {
            tag.putBoolean("Effect", true);
            playEffects = false;
        }
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(4);
        primed = false;
        // Derive the wire state from the block state: buildCircuit may run before
        // NBT (and therefore the saved status) has been read.
        boolean closed = !getBlockState().getValue(StabilizerBlock.TRIPPED) && status == Status.RUNNING;
        var p1 = builder.addInternalNode();
        var p2 = builder.addInternalNode();
        var s1 = builder.addInternalNode();
        var s2 = builder.addInternalNode();
        p1w = builder.connectSwitch(PRIMARY_RESISTANCE, builder.terminalNode(0), p1, closed);
        p2w = builder.connectSwitch(PRIMARY_RESISTANCE, builder.terminalNode(1), p2, closed);
        s1w = builder.connectSwitch(SECONDARY_RESISTANCE, s1, builder.terminalNode(2), closed);
        s2w = builder.connectSwitch(SECONDARY_RESISTANCE, s2, builder.terminalNode(3), closed);
        coupling = builder.couple(ratio, COUPLING_RESISTANCE, p1, p2, s1, s2);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        String key = PowerSafeGrid.MOD_ID + ".goggles.stabilizer.";
        tooltip.add(Component.translatable(key + "status_header").withStyle(ChatFormatting.GOLD));
        ChatFormatting statusColor;
        String statusKey;
        if (burnTimer >= 0) {
            statusColor = ChatFormatting.DARK_RED;
            statusKey = key + "status_burning";
        } else {
            switch (status) {
                case RUNNING -> {
                    statusColor = ChatFormatting.GREEN;
                    statusKey = key + "status_running";
                }
                case OVERLOAD -> {
                    statusColor = ChatFormatting.RED;
                    statusKey = key + "status_overload";
                }
                default -> {
                    statusColor = ChatFormatting.YELLOW;
                    statusKey = key + "status_voltage";
                }
            }
        }
        tooltip.add(Component.translatable(statusKey).withStyle(statusColor));
        tooltip.add(line(key + "target", String.format("%.0f V", targetVoltage)));
        tooltip.add(line(key + "input", String.format("%.0f V", getInputVoltage())));
        tooltip.add(line(key + "current", String.format("%.1f A", getOutputCurrent())));
        tooltip.add(line(key + "window", String.format("%.0f - %.0f V", minVoltage, maxVoltage)));
        if (status != Status.RUNNING)
            tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".goggles.reset_hint").withStyle(ChatFormatting.AQUA));
        return true;
    }

    private static Component line(String translationKey, String value) {
        return Component.translatable(translationKey)
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }
}
