package org.powersafegrid.electric;

import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.powersafegrid.PowerSafeGrid;

public class PSGItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(PowerSafeGrid.MOD_ID);

    public static final DeferredItem<BlockItem> VOLTAGE_RELAY =
            ITEMS.registerSimpleBlockItem(PSGBlocks.VOLTAGE_RELAY);

    public static final DeferredItem<BlockItem> STABILIZER_120 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.STABILIZER_120);

    public static final DeferredItem<BlockItem> STABILIZER_240 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.STABILIZER_240);

    public static final DeferredItem<BlockItem> CREATIVE_STABILIZER_120 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.CREATIVE_STABILIZER_120);

    public static final DeferredItem<BlockItem> CREATIVE_STABILIZER_240 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.CREATIVE_STABILIZER_240);

    public static final DeferredItem<BlockItem> AUTO_STABILIZER_120 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.AUTO_STABILIZER_120);

    public static final DeferredItem<BlockItem> AUTO_STABILIZER_240 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.AUTO_STABILIZER_240);

    public static final DeferredItem<BlockItem> INVERTER =
            ITEMS.registerSimpleBlockItem(PSGBlocks.INVERTER);

    public static final DeferredItem<BlockItem> MINI_INVERTER =
            ITEMS.registerSimpleBlockItem(PSGBlocks.MINI_INVERTER);

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
