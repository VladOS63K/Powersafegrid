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
 * the input voltage stays inside the working window. Leaving the window or an
 * overcurrent latches the device off until it is reset with a screwdriver/wrench.
 */
public class StabilizerBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {
    public static final float PRIMARY_RESISTANCE = 0.1f;
    public static final float SECONDARY_RESISTANCE = 0.1f;
    public static final float COUPLING_RESISTANCE = 0.5f;
    public static final float OVERLOAD_CURRENT = 60f;
    public static final float EXPLODE_CURRENT = 100f;
    public static final int OVERLOAD_CONFIRM_TICKS = 5;
    public static final int EXPLODE_CONFIRM_TICKS = 10;
    public static final float RATIO_SLEW = 0.08f;
    public static final float MIN_RATIO = 0.25f;
    public static final float MAX_RATIO = 4f;

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
        return status;
    }

    public boolean isTripped() {
        return status != Status.RUNNING;
    }

    public double getInputVoltage() {
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(1))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(0).getVoltage() - electricBehaviour.getTerminal(1).getVoltage());
    }

    public double getOutputCurrent() {
        if (s1w == null || status != Status.RUNNING)
            return 0;
        return Math.abs(s1w.current());
    }

    public float getTargetVoltage() {
        return targetVoltage;
    }

    private void trip(Status newStatus) {
        status = newStatus;
        if (p1w != null && p2w != null && s1w != null && s2w != null) {
            p1w.setState(false);
            p2w.setState(false);
            s1w.setState(false);
            s2w.setState(false);
        }
        overloadTimer = 0;
        playEffects = true;
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(StabilizerBlock.TRIPPED, true));
        ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 1f, 0.8f);
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
        status = Status.RUNNING;
        if (p1w != null && p2w != null && s1w != null && s2w != null) {
            p1w.setState(true);
            p2w.setState(true);
            s1w.setState(true);
            s2w.setState(true);
        }
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
    public void electricalTick() {
        applyPower(p1w);
        applyPower(s1w);
        if (level.isClientSide || p1w == null || coupling == null)
            return;
        if (!p1w.isConverged())
            return;
        if (status != Status.RUNNING)
            return;

        double uIn = getInputVoltage();
        double iOut = getOutputCurrent();

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

        if (uIn > 5 && (uIn < minVoltage || uIn > maxVoltage)) {
            trip(Status.VOLTAGE);
            return;
        }

        // Regulate the output by adjusting the coupling ratio.
        if (uIn > 5) {
            float targetRatio = Mth.clamp((float) (targetVoltage / uIn), MIN_RATIO, MAX_RATIO);
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
        if (playEffects) {
            tag.putBoolean("Effect", true);
            playEffects = false;
        }
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(4);
        boolean closed = status == Status.RUNNING;
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
        ChatFormatting statusColor = switch (status) {
            case RUNNING -> ChatFormatting.GREEN;
            case OVERLOAD -> ChatFormatting.RED;
            case VOLTAGE -> ChatFormatting.YELLOW;
        };
        tooltip.add(Component.translatable(key + "status_" + status.name().toLowerCase()).withStyle(statusColor));
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
