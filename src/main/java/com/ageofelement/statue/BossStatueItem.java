package com.ageofelement.statue;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * **Boss 雕像**物品（2026-11-06 改）：**永久带附魔光效**（用户口径 1：「boss 雕像的物品贴图加上附魔光效」）。
 *
 * <p>光效用 {@code isFoil} 恒真实现 —— **不是真附魔**：不占附魔槽、不会被砂轮磨掉、不影响任何结算。</p>
 */
public class BossStatueItem extends StatueItem {

    public BossStatueItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected String bindHintKey() {
        return "tooltip.age_of_element_statue.boss_statue.bind";
    }

    @Override
    protected boolean showsGloryLine() {
        return true;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
