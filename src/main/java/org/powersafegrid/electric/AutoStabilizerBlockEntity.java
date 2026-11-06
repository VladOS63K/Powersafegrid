package org.powersafegrid.electric;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class AutoStabilizerBlockEntity extends StabilizerBlockEntity {

    public AutoStabilizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electricBehaviour.reducedSync();
    }

    @Override
    protected boolean autoRecover() {
        return true;
    }

    @Override
    protected int autoRecoverDelay() {
        // 3 seconds of normal input before the stabilizer switches back on.
        return 60;
    }
}
