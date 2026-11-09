package org.powersafegrid.electric.panel;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Hidden rendering helper block for the panel BER: a breaker or relay module
 * model. Never placed in the world, has no item and no loot.
 */
public class PanelModuleBlock extends Block {
    public static final IntegerProperty RATING = IntegerProperty.create("rating", 1, 4);

    private final boolean breaker;

    public PanelModuleBlock(Properties properties, boolean breaker) {
        super(properties);
        this.breaker = breaker;
        if (breaker)
            registerDefaultState(defaultBlockState().setValue(RATING, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        if (breaker)
            builder.add(RATING);
    }
}
