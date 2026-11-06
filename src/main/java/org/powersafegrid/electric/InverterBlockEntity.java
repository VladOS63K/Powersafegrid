package org.powersafegrid.electric;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.collections.ModdedSoundEvents;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.TransformerCoupling;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

/**
 * Adjustable autotransformer ("inverter"). Converts the input voltage (up to
 * the block's rated maximum) to any fraction between 0 and 100 % of it,
 * selected with a scroll value box. Protection mirrors the stabilizer:
 * moderate overvoltage latches the device off, severe overvoltage (3x the
 * rated maximum) burns it up, overcurrent smokes and latches off / explodes.
 */
public class InverterBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {
    public static final float PRIMARY_RESISTANCE = 0.05f;
    public static final float SECONDARY_RESISTANCE = 0.05f;
    public static final float COUPLING_RESISTANCE = 0.2f;
    public static final float BURN_VOLTAGE_MULTIPLIER = 3f;
    public static final int TRIP_CONFIRM_TICKS = 5;
    public static final int EXPLODE_CONFIRM_TICKS = 10;
    public static final int BURN_TICKS = 40;
    public static final float RATIO_SLEW = 0.15f;
    public static final float MIN_RATIO = 0f;

    public enum Status {
        RUNNING,   // норма
        OVERLOAD,  // перегруз
        VOLTAGE;   // перенапряжение

        public static final Status[] VALUES = values();
    }

    private final boolean multiblock;
    private final float maxInput;
    private final float overloadCurrent;
    private final float explodeCurrent;

    private ScrollValueBehaviour percentSetting;
    private SwitchedWire p1w;
    private SwitchedWire p2w;
    private SwitchedWire s1w;
    private SwitchedWire s2w;
    private TransformerCoupling coupling;

    private Status status = Status.RUNNING;
    private float ratio = 1f;
    private int tripTimer;
    private int overloadTimer;
    private int burnTimer = -1;
    private boolean primed;
    private boolean playEffects;

    /** Fast-synced display values, only meaningful on the client. */
    private float clientUIn;
    private float clientUOut;
    private float clientIOut;
    private Status clientStatus = Status.RUNNING;
    private boolean clientBurning;
    private int syncTimer;
    private int formationCheckDelay = 5;

    public InverterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        if (state.getBlock() instanceof InverterBlock block) {
            multiblock = block.isMultiblock();
            maxInput = block.getMaxInput();
            overloadCurrent = block.getOverloadCurrent();
            explodeCurrent = block.getExplodeCurrent();
        } else {
            multiblock = false;
            maxInput = 4000f;
            overloadCurrent = 60f;
            explodeCurrent = 100f;
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electricBehaviour.reducedSync();
        if (!isController())
            return;
        percentSetting = new ScrollValueBehaviour(
                Component.translatable(PowerSafeGrid.MOD_ID + ".devices.inverter.setting"),
                this, new BoxTransform())
                .between(0, 100)
                .withFormatter(i -> i + " %");
        percentSetting.value = 100;
        behaviours.add(percentSetting);
    }

    /** Whether this block entity actually owns the electric circuit. */
    public boolean isController() {
        return !multiblock || getBlockState().getValue(InverterBlock.CONTROLLER);
    }

    /** Called by the block when the multiblock controller flag flips. */
    public void onControllerChanged() {
        if (level != null && !level.isClientSide) {
            ModdedSoundEvents.CONTACTOR_OFF.playOnServer(level, worldPosition, 0.7f, 1.4f);
            PowerSafeGrid.LOGGER.info("Inverter at {} controller={}", worldPosition.toShortString(),
                    getBlockState().getValue(InverterBlock.CONTROLLER));
        }
        electricBehaviour.rebuildCircuit(true);
        notifyUpdate();
    }

    public float getPercent() {
        return percentSetting != null ? Mth.clamp(percentSetting.value, 0, 100) : 100;
    }

    public Status getStatus() {
        if (level != null && level.isClientSide)
            return clientBurning ? Status.OVERLOAD : clientStatus;
        return burnTimer >= 0 ? Status.OVERLOAD : status;
    }

    public boolean isTripped() {
        return status != Status.RUNNING || burnTimer >= 0;
    }

    public boolean isBurning() {
        if (level != null && level.isClientSide)
            return clientBurning;
        return burnTimer >= 0;
    }

    /**
     * Whether both terminals of a measurement pair actually have wire
     * connections. Protection only reacts to voltage measured across a
     * complete pair - a single floating wire held at 25 kV by its source is
     * not a fault.
     */
    protected boolean pairWired(int a, int b) {
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(b))
            return false;
        var connections = electricBehaviour.getConnections();
        var connsA = connections.get(new BlockWireEndpoint(worldPosition, a));
        var connsB = connections.get(new BlockWireEndpoint(worldPosition, b));
        return connsA != null && !connsA.isEmpty() && connsB != null && !connsB.isEmpty();
    }

    public double getInputVoltage() {
        if (level != null && level.isClientSide)
            return clientUIn;
        if (!pairWired(0, 1))
            return 0;
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(1))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(0).getVoltage() - electricBehaviour.getTerminal(1).getVoltage());
    }

    public double getOutputVoltage() {
        if (level != null && level.isClientSide)
            return clientUOut;
        if (!pairWired(2, 3))
            return 0;
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(3))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(2).getVoltage() - electricBehaviour.getTerminal(3).getVoltage());
    }

    public double monitoredVoltage() {
        return Math.max(getInputVoltage(), getOutputVoltage());
    }

    public double getOutputCurrent() {
        if (level != null && level.isClientSide)
            return clientIOut;
        if (s1w == null || status != Status.RUNNING || burnTimer >= 0)
            return 0;
        return Math.abs(s1w.current());
    }

    public float getMaxInput() {
        return maxInput;
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
        tripTimer = 0;
        overloadTimer = 0;
        playEffects = true;
        PowerSafeGrid.LOGGER.info("Inverter at {} tripped ({}), input {} V, output current {} A",
                worldPosition.toShortString(), newStatus, String.format("%.0f", getInputVoltage()),
                String.format("%.1f", getOutputCurrent()));
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(InverterBlock.TRIPPED, true));
        ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 1f, 0.8f);
        setChanged();
        notifyUpdate();
    }

    private void startBurn() {
        if (burnTimer >= 0)
            return;
        status = Status.VOLTAGE;
        applyWireStates(false);
        burnTimer = BURN_TICKS + level.random.nextInt(20);
        playEffects = true;
        PowerSafeGrid.LOGGER.warn("Inverter at {} is burning up, monitored voltage {} V",
                worldPosition.toShortString(), String.format("%.0f", monitoredVoltage()));
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(InverterBlock.TRIPPED, true));
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
        tripTimer = 0;
        overloadTimer = 0;
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(InverterBlock.TRIPPED, false));
        ModdedSoundEvents.CONTACTOR_ON.playOnServer(level, worldPosition, 0.8f, 1f);
        setChanged();
        notifyUpdate();
    }

    @Override
    public void unpaused() {
        electricBehaviour.rebuildCircuit(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;
        if (level.isClientSide) {
            if (burnTimer >= 0 && level.random.nextInt(3) == 0) {
                var center = worldPosition.getCenter();
                level.addParticle(ParticleTypes.SMOKE, center.x, center.y + 0.5, center.z, 0, 0.02, 0);
            }
            return;
        }
        // Safety net: re-evaluate multiblock formation shortly after (re)loading,
        // in case a placement/removal update was missed.
        if (formationCheckDelay > 0 && --formationCheckDelay == 0
                && getBlockState().getBlock() instanceof InverterBlock block && block.isMultiblock()
                && level instanceof net.minecraft.server.level.ServerLevel server) {
            block.updateFormation(server, worldPosition);
        }
        if (++syncTimer >= 4) {
            syncTimer = 0;
            sendData();
        }
    }

    @Override
    public void electricalTick() {
        applyPower(p1w);
        applyPower(s1w);
        if (level.isClientSide || p1w == null || coupling == null)
            return;

        if (!primed && p1w.getNetwork() != null) {
            primed = true;
            applyWireStates(burnTimer < 0 && status == Status.RUNNING);
        }

        if (burnTimer >= 0) {
            if (--burnTimer <= 0)
                explode();
            return;
        }

        if (status != Status.RUNNING)
            return;

        double uIn = getInputVoltage();
        double iOut = getOutputCurrent();

        if (monitoredVoltage() > BURN_VOLTAGE_MULTIPLIER * maxInput) {
            startBurn();
            return;
        }

        if (iOut >= explodeCurrent) {
            if (++overloadTimer >= EXPLODE_CONFIRM_TICKS)
                explode();
            return;
        }
        if (iOut > overloadCurrent) {
            if (++overloadTimer >= TRIP_CONFIRM_TICKS)
                trip(Status.OVERLOAD);
            return;
        }
        overloadTimer = 0;

        if (uIn > 5 && uIn > maxInput) {
            if (++tripTimer >= TRIP_CONFIRM_TICKS)
                trip(Status.VOLTAGE);
            return;
        }
        tripTimer = 0;

        // Regulate: hold the output at percent % of the input voltage.
        if (uIn > 5 && p1w.isConverged()) {
            float wanted = (float) uIn * getPercent() / 100f;
            double uOut = getOutputVoltage();
            float targetRatio;
            if (uOut > 1) {
                float correction = Mth.clamp((float) (wanted / uOut), 0.8f, 1.25f);
                targetRatio = ratio * correction;
            } else {
                targetRatio = getPercent() / 100f;
            }
            targetRatio = Mth.clamp(targetRatio, MIN_RATIO, 1f);

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
        if (clientPacket) {
            clientUIn = tag.getFloat("ClUIn");
            clientUOut = tag.getFloat("ClUOut");
            clientIOut = tag.getFloat("ClIOut");
            clientStatus = Status.VALUES[tag.getByte("ClStatus") % Status.VALUES.length];
            clientBurning = tag.getBoolean("ClBurning");
        }
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
        if (clientPacket) {
            tag.putFloat("ClUIn", (float) getInputVoltage());
            tag.putFloat("ClUOut", (float) getOutputVoltage());
            tag.putFloat("ClIOut", (float) getOutputCurrent());
            tag.putByte("ClStatus", (byte) status.ordinal());
            tag.putBoolean("ClBurning", burnTimer >= 0);
        }
        if (playEffects) {
            tag.putBoolean("Effect", true);
            playEffects = false;
        }
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        if (!isController()) {
            // A dormant multiblock shell: no circuit at all.
            builder.setTerminalCount(0);
            return;
        }
        builder.setTerminalCount(4);
        primed = false;
        boolean closed = !getBlockState().getValue(InverterBlock.TRIPPED) && status == Status.RUNNING;
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
        String key = PowerSafeGrid.MOD_ID + ".goggles.inverter.";
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
        tooltip.add(line(key + "ratio", String.format("%d %%", (int) getPercent())));
        tooltip.add(line(key + "input", String.format("%.0f V", getInputVoltage())));
        tooltip.add(line(key + "output", String.format("%.0f V", getOutputVoltage())));
        tooltip.add(line(key + "current", String.format("%.1f A", getOutputCurrent())));
        tooltip.add(line(key + "max_input", String.format("%.0f V", maxInput)));
        if (status != Status.RUNNING)
            tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".goggles.reset_hint").withStyle(ChatFormatting.AQUA));
        return true;
    }

    private static Component line(String translationKey, String value) {
        return Component.translatable(translationKey)
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }

    public static class BoxTransform extends CenteredSideValueBoxTransform {
        public BoxTransform() {
            super((state, dir) -> dir.getOpposite() == state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8.0f, 8.0f, 15.5f);
        }
    }
}
