package org.powersafegrid.electric;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.patryk3211.powergrid.electricity.base.HorizontalElectricBlock;
import org.patryk3211.powergrid.electricity.wire.powercord.IAcceptCord;
import org.powersafegrid.PowerSafeGrid;

import java.util.List;

/**
 * Creative voltage stabilizer: outputs its target voltage from an ideal
 * voltage source, ignoring whatever is present on the input side. No
 * overvoltage, undervoltage or overcurrent protection applies.
 */
@MethodsReturnNonnullByDefault
public class CreativeStabilizerBlock extends HorizontalElectricBlock implements IBE<CreativeStabilizerBlockEntity>, IAcceptCord {

    private final float targetVoltage;

    public CreativeStabilizerBlock(Properties properties, float targetVoltage) {
        super(properties);
        this.targetVoltage = targetVoltage;
        setTerminalCollection(horizontalNorthTerminals(this, StabilizerBlock.TERMINALS_NORTH, StabilizerBlock.SHAPE_NORTH));
    }

    public float getTargetVoltage() {
        return targetVoltage;
    }

    @Override
    public Class<CreativeStabilizerBlockEntity> getBlockEntityClass() {
        return CreativeStabilizerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CreativeStabilizerBlockEntity> getBlockEntityType() {
        return PSGBlockEntities.CREATIVE_STABILIZER.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.creative_stabilizer.1",
                String.format("%.0f", targetVoltage)).withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.creative_stabilizer.2").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".tooltip.creative_stabilizer.3").withStyle(ChatFormatting.GRAY));
    }
}
