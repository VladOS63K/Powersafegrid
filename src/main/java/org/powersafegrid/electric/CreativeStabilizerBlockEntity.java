package org.powersafegrid.electric;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.node.ProvidedVoltageSourceCoupling;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

/**
 * Creative stabilizer block entity: holds an ideal voltage source between the
 * output terminals. The input side is only measured for display purposes and
 * has no effect on the output.
 */
public class CreativeStabilizerBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {
    private final float targetVoltage;

    private ProvidedVoltageSourceCoupling source;

    /** Fast-synced display values, only meaningful on the client. */
    private float clientUIn;
    private float clientUOut;
    private float clientIOut;
    private int syncTimer;

    public CreativeStabilizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        if (state.getBlock() instanceof CreativeStabilizerBlock block) {
            targetVoltage = block.getTargetVoltage();
        } else {
            targetVoltage = 120f;
        }
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electricBehaviour.reducedSync();
    }

    public double getInputVoltage() {
        if (level != null && level.isClientSide)
            return clientUIn;
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(1))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(0).getVoltage() - electricBehaviour.getTerminal(1).getVoltage());
    }

    public double getOutputVoltage() {
        if (level != null && level.isClientSide)
            return clientUOut;
        if (electricBehaviour == null || !electricBehaviour.hasTerminal(3))
            return 0;
        return Math.abs(electricBehaviour.getTerminal(2).getVoltage() - electricBehaviour.getTerminal(3).getVoltage());
    }

    public double getOutputCurrent() {
        if (level != null && level.isClientSide)
            return clientIOut;
        if (source == null)
            return 0;
        return Math.abs(source.getCurrent());
    }

    public float getTargetVoltage() {
        return targetVoltage;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;
        // Push fresh display values to clients so the goggles readout is live.
        if (++syncTimer >= 4) {
            syncTimer = 0;
            sendData();
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            clientUIn = tag.getFloat("ClUIn");
            clientUOut = tag.getFloat("ClUOut");
            clientIOut = tag.getFloat("ClIOut");
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            tag.putFloat("ClUIn", (float) getInputVoltage());
            tag.putFloat("ClUOut", (float) getOutputVoltage());
            tag.putFloat("ClIOut", (float) getOutputCurrent());
        }
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(4);
        // The input terminals stay passive (measurement only). The output is an
        // ideal voltage source: constant target voltage, unlimited current.
        source = new ProvidedVoltageSourceCoupling(builder.terminalNode(2), builder.terminalNode(3), 1e-4f);
        source.setVoltage(targetVoltage);
        builder.add(source);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        String key = PowerSafeGrid.MOD_ID + ".goggles.stabilizer.";
        tooltip.add(Component.translatable(key + "creative_header").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(line(key + "target", String.format("%.0f V", targetVoltage)));
        tooltip.add(line(key + "input", String.format("%.0f V", getInputVoltage())));
        tooltip.add(line(key + "output", String.format("%.0f V", getOutputVoltage())));
        tooltip.add(line(key + "current", String.format("%.1f A", getOutputCurrent())));
        return true;
    }

    private static Component line(String translationKey, String value) {
        return Component.translatable(translationKey)
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }
}
