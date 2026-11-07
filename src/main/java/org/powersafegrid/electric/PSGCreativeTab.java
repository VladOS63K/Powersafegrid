package org.powersafegrid.electric;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.powersafegrid.PowerSafeGrid;

public class PSGCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PowerSafeGrid.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + PowerSafeGrid.MOD_ID + ".main"))
                    .icon(() -> new ItemStack(PSGBlocks.VOLTAGE_RELAY.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(PSGBlocks.VOLTAGE_RELAY.get());
                        output.accept(PSGBlocks.STABILIZER_120.get());
                        output.accept(PSGBlocks.STABILIZER_240.get());
                        output.accept(PSGBlocks.AUTO_STABILIZER_120.get());
                        output.accept(PSGBlocks.AUTO_STABILIZER_240.get());
                        output.accept(PSGBlocks.CREATIVE_STABILIZER_120.get());
                        output.accept(PSGBlocks.CREATIVE_STABILIZER_240.get());
                        output.accept(PSGBlocks.MINI_INVERTER.get());
                    })
                    .build());

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
