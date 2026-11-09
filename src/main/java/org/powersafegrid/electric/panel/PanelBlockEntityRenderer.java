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
            // front face of the thin slab (flush against the wall behind it)
            // Just outside the front face of the slab, centered per slot.
            var offset = switch (facing) {
                case NORTH -> new net.minecraft.world.phys.Vec3(lx, 0.0, -0.51);
                case SOUTH -> new net.minecraft.world.phys.Vec3(-lx, 0.0, 0.51);
                case WEST -> new net.minecraft.world.phys.Vec3(-0.51, 0.0, lx);
                case EAST -> new net.minecraft.world.phys.Vec3(0.51, 0.0, -lx);
                default -> new net.minecraft.world.phys.Vec3(lx, 0.0, -0.51);
            };
            float yRot = switch (facing) {
                case NORTH -> 180f;
                case SOUTH -> 0f;
                case WEST -> 270f;
                case EAST -> 90f;
                default -> 180f;
            };

            poseStack.pushPose();
            // The dispatcher already translates the pose stack to the block
            // center; only apply the slot offset here.
            poseStack.translate(offset.x, offset.y, offset.z);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yRot));
            poseStack.scale(0.4f, 0.4f, 0.4f);
            // Center the unit-sized item model on the anchor point.
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            int slotLight = LevelRenderer.getLightColor(level, panel.getBlockPos());
            itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED, slotLight,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    poseStack, bufferSource, level, 0);
            poseStack.popPose();
        }
    }
}
