package org.powersafegrid.electric.panel;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.powersafegrid.electric.PSGBlockEntities;

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
        var facing = panel.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
        var itemRenderer = Minecraft.getInstance().getItemRenderer();

        for (int i = 0; i < slots; i++) {
            ItemStack stack = panel.getModuleStack(i);
            if (stack.isEmpty())
                continue;
            double lx = (i + 0.5) / slots - 0.5;
            var offset = switch (facing) {
                case NORTH -> new net.minecraft.world.phys.Vec3(lx, 0.5, -0.44);
                case SOUTH -> new net.minecraft.world.phys.Vec3(-lx, 0.5, 0.44);
                case WEST -> new net.minecraft.world.phys.Vec3(-0.44, 0.5, lx);
                case EAST -> new net.minecraft.world.phys.Vec3(0.44, 0.5, -lx);
                default -> new net.minecraft.world.phys.Vec3(lx, 0.5, -0.44);
            };
            float yRot = switch (facing) {
                case NORTH -> 180f;
                case SOUTH -> 0f;
                case WEST -> 270f;
                case EAST -> 90f;
                default -> 180f;
            };

            poseStack.pushPose();
            poseStack.translate(panel.getBlockPos().getX() + 0.5 + offset.x,
                    panel.getBlockPos().getY() + offset.y,
                    panel.getBlockPos().getZ() + 0.5 + offset.z);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yRot));
            poseStack.scale(0.35f, 0.35f, 0.35f);
            int slotLight = LevelRenderer.getLightColor(level, panel.getBlockPos());
            itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED, slotLight,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    poseStack, bufferSource, level, 0);
            poseStack.popPose();
        }
    }
}
