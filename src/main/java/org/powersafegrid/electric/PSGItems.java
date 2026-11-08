package org.powersafegrid.electric;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

    public static final DeferredItem<BlockItem> MINI_INVERTER =
            ITEMS.registerSimpleBlockItem(PSGBlocks.MINI_INVERTER);

    public static final DeferredItem<BlockItem> PANEL_5 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.PANEL_5);

    public static final DeferredItem<BlockItem> PANEL_10 =
            ITEMS.registerSimpleBlockItem(PSGBlocks.PANEL_10);

    public static final DeferredItem<Item> BREAKER_8A =
            ITEMS.register("breaker_8a", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> BREAKER_10A =
            ITEMS.register("breaker_10a", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> BREAKER_16A =
            ITEMS.register("breaker_16a", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> BREAKER_63A =
            ITEMS.register("breaker_63a", () -> new Item(new Item.Properties()));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
