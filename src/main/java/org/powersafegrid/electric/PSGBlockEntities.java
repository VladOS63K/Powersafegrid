package org.powersafegrid.electric;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.powersafegrid.PowerSafeGrid;

import java.util.function.Supplier;

public class PSGBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, PowerSafeGrid.MOD_ID);

    public static final Supplier<BlockEntityType<VoltageRelayBlockEntity>> VOLTAGE_RELAY =
            BLOCK_ENTITIES.register("voltage_relay", () -> BlockEntityType.Builder.of(
                    (pos, state) -> new VoltageRelayBlockEntity(relayType(), pos, state),
                    PSGBlocks.VOLTAGE_RELAY.get()).build(null));

    public static final Supplier<BlockEntityType<StabilizerBlockEntity>> STABILIZER =
            BLOCK_ENTITIES.register("stabilizer", () -> BlockEntityType.Builder.of(
                    (pos, state) -> new StabilizerBlockEntity(stabilizerType(), pos, state),
                    PSGBlocks.STABILIZER_120.get(),
                    PSGBlocks.STABILIZER_240.get()).build(null));

    private static BlockEntityType<VoltageRelayBlockEntity> relayType() {
        return VOLTAGE_RELAY.get();
    }

    private static BlockEntityType<StabilizerBlockEntity> stabilizerType() {
        return STABILIZER.get();
    }

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
