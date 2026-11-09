package org.powersafegrid.electric.panel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.powersafegrid.electric.PSGBlocks;

public class PanelBlockEntityRenderer implements BlockEntityRenderer<PanelBlockEntity> {

    public PanelBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PanelBlockEntity panel, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {
        int slots = panel.getSlotCount();
        var level = panel.getLevel();
        if (level == null)
            return;
        var facing = panel.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        var blockRenderer = Minecraft.getInstance().getBlockRenderer();

        for (int i = 0; i < slots; i++) {
            BlockState state = panel.getModuleState(i);
            if (state == null)
                continue;
            int span = panel.getModule(i) == PanelBlockEntity.PanelModule.RELAY ? 2 : 1;
            // Slot center across the panel, relative to the pose origin (the
            // block's lower corner). The module sits ~0.2 blocks in front of
            // the panel's front face.
            // Slot 1 is the rightmost column as seen from the front.
            double lx = 0.5 - (i + span / 2.0) / slots;
            double ox = switch (facing) {
                case NORTH, SOUTH -> 0.5 + lx;
                case WEST -> -0.2;
                case EAST -> 1.2;
                default -> 0.5 + lx;
            };
            double oz = switch (facing) {
                case NORTH -> -0.2;
                case SOUTH -> 1.2;
                case WEST, EAST -> 0.5 + lx;
                default -> 0.5 + lx;
            };
            float yRot = switch (facing) {
                case NORTH -> 0f;
                case SOUTH -> 180f;
                case WEST -> 270f;
                case EAST -> 90f;
                default -> 0f;
            };

            poseStack.pushPose();
            poseStack.translate(ox, 0.5, oz);
            poseStack.mulPose(Axis.YP.rotationDegrees(yRot));
            // Scale the module down on the 10-slot panel so it fits its slot.
            float scale = slots > 5 ? 0.55f : 1.0f;
            poseStack.scale(scale, scale, scale);
            int slotLight = LevelRenderer.getLightColor(level, panel.getBlockPos());
            blockRenderer.renderSingleBlock(state, poseStack, bufferSource, slotLight, overlay);
            poseStack.popPose();
            if (span == 2)
                i++;
        }
    }
}
