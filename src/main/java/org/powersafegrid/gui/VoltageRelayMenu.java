package org.powersafegrid.gui;

import com.simibubi.create.foundation.gui.menu.MenuBase;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.powersafegrid.electric.PSGMenus;
import org.powersafegrid.electric.VoltageRelayBlockEntity;

public class VoltageRelayMenu extends MenuBase<VoltageRelayBlockEntity> {
    public VoltageRelayMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(type, id, inv, extraData);
    }

    public VoltageRelayMenu(int id, Inventory inv, VoltageRelayBlockEntity contentHolder) {
        super(PSGMenus.VOLTAGE_RELAY.get(), id, inv, contentHolder);
    }

    @Override
    protected VoltageRelayBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
        var world = Minecraft.getInstance().level;
        var be = world.getBlockEntity(extraData.readBlockPos());
        return be instanceof VoltageRelayBlockEntity relay ? relay : null;
    }

    @Override
    protected void initAndReadInventory(VoltageRelayBlockEntity relay) {
    }

    @Override
    protected void addSlots() {
    }

    @Override
    protected void saveData(VoltageRelayBlockEntity relay) {
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
