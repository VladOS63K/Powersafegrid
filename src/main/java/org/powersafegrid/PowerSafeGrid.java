package org.powersafegrid;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.powersafegrid.electric.PSGBlocks;
import org.powersafegrid.electric.PSGBlockEntities;
import org.powersafegrid.electric.PSGCreativeTab;
import org.powersafegrid.electric.PSGItems;
import org.powersafegrid.electric.PSGMenus;
import org.powersafegrid.network.PSGPayloads;

@Mod(PowerSafeGrid.MOD_ID)
public class PowerSafeGrid {
    public static final String MOD_ID = "powersafegrid";
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public PowerSafeGrid(IEventBus modEventBus) {
        PSGBlocks.register(modEventBus);
        PSGBlockEntities.register(modEventBus);
        PSGItems.register(modEventBus);
        PSGMenus.register(modEventBus);
        PSGCreativeTab.register(modEventBus);
        PSGPayloads.register(modEventBus);
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
