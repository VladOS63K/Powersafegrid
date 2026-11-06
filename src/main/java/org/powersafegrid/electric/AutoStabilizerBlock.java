package org.powersafegrid.electric;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

/**
 * Automatic stabilizer: identical to the regular stabilizer, but a tripped
 * device recovers by itself once the input voltage (and, after an overload,
 * the situation in general) has been back to normal for a few seconds.
 */
public class AutoStabilizerBlock extends StabilizerBlock {

    public AutoStabilizerBlock(Properties properties, float targetVoltage, float minVoltage, float maxVoltage) {
        super(properties, targetVoltage, minVoltage, maxVoltage);
    }

    @Override
    public BlockEntityType<AutoStabilizerBlockEntity> getBlockEntityType() {
        return PSGBlockEntities.AUTO_STABILIZER.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.auto_stabilizer.1").withStyle(ChatFormatting.GREEN));
    }
}
