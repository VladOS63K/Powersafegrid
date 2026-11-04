package org.powersafegrid.gui;

import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import org.powersafegrid.PowerSafeGrid;
import org.powersafegrid.electric.VoltageRelayBlockEntity;
import org.powersafegrid.network.PSGPayloads;

public class VoltageRelayScreen extends AbstractSimiContainerScreen<VoltageRelayMenu> {
    private static final ResourceLocation BACKGROUND = PowerSafeGrid.asResource("textures/gui/voltage_relay.png");
    private static final int WIDTH = 206;
    private static final int HEIGHT = 108;

    /** Upper bound of the adjustable voltage scale. */
    private static final float V_MAX = 320f;
    private static final float MIN_GAP = 10f;
    private static final int TRACK_X0 = 20;
    private static final int TRACK_W = 166;

    private enum Handle { NONE, LOW, HIGH }

    private Handle dragging = Handle.NONE;
    private Handle selected = Handle.HIGH;

    public VoltageRelayScreen(VoltageRelayMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    private VoltageRelayBlockEntity relay() {
        return menu.contentHolder;
    }

    @Override
    protected void init() {
        setWindowSize(WIDTH, HEIGHT);
        setWindowOffset(-1, 0);
        super.init();
    }

    private int trackX0() {
        return leftPos + TRACK_X0;
    }

    private int valueToX(float value) {
        return trackX0() + Math.round(value / V_MAX * TRACK_W);
    }

    private float xToValue(int x) {
        return Mth.clamp((x - trackX0()) / (float) TRACK_W * V_MAX, 0f, V_MAX);
    }

    private int trackY() {
        return topPos + 58;
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        var relay = relay();
        gfx.blit(BACKGROUND, getLeftOfCentered(WIDTH), topPos, 0, 0, WIDTH, HEIGHT);

        gfx.drawCenteredString(font, title, leftPos + WIDTH / 2, topPos + 6, 0x3F3F3F);

        // Status line
        ChatFormatting color;
        Component status;
        switch (relay.getStatus()) {
            case CLOSED -> {
                color = ChatFormatting.DARK_GREEN;
                status = Component.translatable(PowerSafeGrid.MOD_ID + ".gui.relay.status_closed");
            }
            case OPEN -> {
                color = ChatFormatting.GOLD;
                status = Component.translatable(PowerSafeGrid.MOD_ID + ".gui.relay.status_open");
            }
            default -> {
                color = ChatFormatting.DARK_RED;
                status = Component.translatable(PowerSafeGrid.MOD_ID + ".gui.relay.status_overload");
            }
        }
        gfx.drawString(font, Component.literal("\u25CF ").withStyle(color).append(status), leftPos + 10, topPos + 20, 0x3F3F3F, false);

        // Live measurements
        gfx.drawString(font,
                Component.translatable(PowerSafeGrid.MOD_ID + ".gui.relay.measured",
                        String.format("%.0f", relay.getInputVoltage()), String.format("%.1f", relay.getRelayCurrent())),
                leftPos + 10, topPos + 32, 0x4C4C6C, false);

        // Track
        int x0 = trackX0();
        int y = trackY();
        gfx.fill(x0, y, x0 + TRACK_W, y + 3, 0xFF565656);
        for (int v = 0; v <= V_MAX; v += 40) {
            int tx = valueToX(v);
            gfx.fill(tx, y - 3, tx + 1, y, 0xFF8B8B8B);
        }
        gfx.drawString(font, "0", valueToX(0) - 2, y + 7, 0x606060, false);
        gfx.drawString(font, "160", valueToX(160) - 6, y + 7, 0x606060, false);
        gfx.drawString(font, "320", valueToX(320) - 6, y + 7, 0x606060, false);
        gfx.drawString(font, "V", valueToX(320) + 8, y - 3, 0x606060, false);

        // Handles
        drawHandle(gfx, valueToX(relay.getLowLimit()), y, selected == Handle.LOW, 0xFF3D6DD8);
        drawHandle(gfx, valueToX(relay.getHighLimit()), y, selected == Handle.HIGH, 0xFFC8442E);
        String low = String.format("%.0f", relay.getLowLimit());
        String high = String.format("%.0f", relay.getHighLimit());
        gfx.drawCenteredString(font, low, valueToX(relay.getLowLimit()), y - 24, 0x3D6DD8);
        gfx.drawCenteredString(font, high, valueToX(relay.getHighLimit()), y - 24, 0xC8442E);

        // Hints
        gfx.drawString(font, Component.translatable(PowerSafeGrid.MOD_ID + ".gui.relay.hint"), leftPos + 10, topPos + HEIGHT - 12, 0x8B8B8B, false);
    }

    private void drawHandle(GuiGraphics gfx, int x, int y, boolean sel, int color) {
        int borderColor = sel ? 0xFF2B2B2B : 0xFF3A3A3A;
        gfx.fill(x - 3, y - 12, x + 3, y + 14, borderColor);
        gfx.fill(x - 2, y - 11, x + 2, y + 13, color);
        if (sel)
            gfx.fill(x - 2, y - 11, x + 2, y - 9, 0xFFFFFFFF);
    }

    private Handle handleAt(double mouseX, double mouseY) {
        var relay = relay();
        int y = trackY();
        if (mouseY < y - 14 || mouseY > y + 16)
            return Handle.NONE;
        if (Math.abs(mouseX - valueToX(relay.getLowLimit())) <= 4)
            return Handle.LOW;
        if (Math.abs(mouseX - valueToX(relay.getHighLimit())) <= 4)
            return Handle.HIGH;
        return Handle.NONE;
    }

    private void applyValue(Handle handle, float value) {
        var relay = relay();
        float low = relay.getLowLimit();
        float high = relay.getHighLimit();
        value = Math.round(value);
        if (handle == Handle.LOW)
            low = Mth.clamp(value, 0f, high - MIN_GAP);
        else
            high = Mth.clamp(value, low + MIN_GAP, V_MAX);
        relay.setRangeClient(low, high);
    }

    private void sendChanges() {
        var relay = relay();
        PSGPayloads.sendToServer(new PSGPayloads.SetRelayRangePayload(relay.getBlockPos(), relay.getLowLimit(), relay.getHighLimit()));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Handle handle = handleAt(mouseX, mouseY);
        if (handle != Handle.NONE && button == 0) {
            dragging = handle;
            selected = handle;
            applyValue(handle, (float) xToValue((int) mouseX));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging != Handle.NONE) {
            applyValue(dragging, (float) xToValue((int) mouseX));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != Handle.NONE && button == 0) {
            dragging = Handle.NONE;
            sendChanges();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Handle handle = handleAt(mouseX, mouseY);
        if (handle != Handle.NONE) {
            selected = handle;
            float step = (float) (hasShiftDown() ? 10 : 1) * (scrollY > 0 ? 1 : -1);
            var relay = relay();
            float value = handle == Handle.LOW ? relay.getLowLimit() : relay.getHighLimit();
            applyValue(handle, value + step);
            sendChanges();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
