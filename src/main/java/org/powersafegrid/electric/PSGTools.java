package org.powersafegrid.electric;

import com.simibubi.create.AllItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Tools that can reset faulted devices: Create's wrench
 * and, if installed, the screwdriver from Create: Crafts &amp; Additions.
 */
public class PSGTools {
    private static final boolean CCA_LOADED = ModList.get().isLoaded("createaddition");
    private static Item ccaScrewdriver;

    public static boolean isResetTool(ItemStack stack) {
        if (stack.isEmpty())
            return false;
        if (AllItems.WRENCH.isIn(stack))
            return true;
        return CCA_LOADED && screwdriver() != null && stack.is(screwdriver());
    }

    private static Item screwdriver() {
        if (ccaScrewdriver == null) {
            ccaScrewdriver = BuiltInRegistries.ITEM
                    .get(ResourceLocation.fromNamespaceAndPath("createaddition", "screwdriver"));
            if (ccaScrewdriver == net.minecraft.world.item.Items.AIR)
                ccaScrewdriver = null;
        }
        return ccaScrewdriver;
    }
}
