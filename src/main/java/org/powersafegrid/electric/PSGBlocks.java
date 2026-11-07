package org.powersafegrid.electric;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.powersafegrid.PowerSafeGrid;

public class PSGBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(PowerSafeGrid.MOD_ID);

    public static final DeferredBlock<VoltageRelayBlock> VOLTAGE_RELAY =
            BLOCKS.register("voltage_relay", () -> new VoltageRelayBlock(properties()));

    public static final DeferredBlock<StabilizerBlock> STABILIZER_120 =
            BLOCKS.register("stabilizer_120", () -> new StabilizerBlock(properties(), 120f, 90f, 130f));

    public static final DeferredBlock<StabilizerBlock> STABILIZER_240 =
            BLOCKS.register("stabilizer_240", () -> new StabilizerBlock(properties(), 240f, 190f, 250f));

    public static final DeferredBlock<CreativeStabilizerBlock> CREATIVE_STABILIZER_120 =
            BLOCKS.register("creative_stabilizer_120",
                    () -> new CreativeStabilizerBlock(properties().mapColor(MapColor.COLOR_PURPLE), 120f));

    public static final DeferredBlock<CreativeStabilizerBlock> CREATIVE_STABILIZER_240 =
            BLOCKS.register("creative_stabilizer_240",
                    () -> new CreativeStabilizerBlock(properties().mapColor(MapColor.COLOR_PURPLE), 240f));

    public static final DeferredBlock<AutoStabilizerBlock> AUTO_STABILIZER_120 =
            BLOCKS.register("auto_stabilizer_120", () -> new AutoStabilizerBlock(properties(), 120f, 90f, 130f));

    public static final DeferredBlock<AutoStabilizerBlock> AUTO_STABILIZER_240 =
            BLOCKS.register("auto_stabilizer_240", () -> new AutoStabilizerBlock(properties(), 240f, 190f, 250f));

    public static final DeferredBlock<InverterBlock> MINI_INVERTER =
            BLOCKS.register("mini_inverter", () -> new InverterBlock(properties(), false, 1000f, 15f, 25f));

    public static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(2.0f, 6.0f)
                .sound(SoundType.METAL);
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
