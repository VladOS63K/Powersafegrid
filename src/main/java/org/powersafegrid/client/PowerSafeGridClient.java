package org.powersafegrid.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.powersafegrid.PowerSafeGrid;
import org.powersafegrid.electric.PSGBlockEntities;
import org.powersafegrid.electric.PSGMenus;
import org.powersafegrid.gui.VoltageRelayScreen;

@Mod(value = PowerSafeGrid.MOD_ID, dist = Dist.CLIENT)
public class PowerSafeGridClient {
    public PowerSafeGridClient(IEventBus modEventBus) {
        modEventBus.addListener((RegisterMenuScreensEvent event) ->
                event.register(PSGMenus.VOLTAGE_RELAY.get(), VoltageRelayScreen::new));
        modEventBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(PSGBlockEntities.PANEL.get(),
                        org.powersafegrid.electric.panel.PanelBlockEntityRenderer::new));
    }
}
