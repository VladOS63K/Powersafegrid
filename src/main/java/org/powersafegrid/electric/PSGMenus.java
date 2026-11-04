package org.powersafegrid.electric;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.powersafegrid.PowerSafeGrid;
import org.powersafegrid.gui.VoltageRelayMenu;

import java.util.function.Supplier;

public class PSGMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, PowerSafeGrid.MOD_ID);

    public static final Supplier<MenuType<VoltageRelayMenu>> VOLTAGE_RELAY =
            MENUS.register("voltage_relay", () -> new MenuType<>(
                    (IContainerFactory<VoltageRelayMenu>) (id, inv, buf) -> new VoltageRelayMenu(id, inv, buf),
                    FeatureFlags.VANILLA_SET));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
