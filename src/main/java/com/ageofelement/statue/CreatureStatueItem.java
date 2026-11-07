package com.ageofelement.statue;

import net.minecraft.world.level.block.Block;

/**
 * **生物雕像**（2026-11-06 用户口径 4）：纯装饰，**无附魔光效、无任何效果**。
 *
 * <p>合成配方 = 原 Boss 雕像那套（石砖×4 + 石英×4 + 紫水晶碎片×1）。</p>
 *
 * <p><b>shift + 右键生物</b> ⇒ 把手里这件雕像"拍"成那只生物的雕像（写 {@code BossId}）。
 * 只在服务端改 NBT，客户端拿同步结果；没有数量/种类限制，抓谁像谁。</p>
 */
public class CreatureStatueItem extends StatueItem {

    public CreatureStatueItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected String boundWord() {
        return "生物";
    }

    @Override
    protected String bindHintKey() {
        return "tooltip.age_of_element_statue.creature_statue.bind";
    }

    @Override
    protected String emptyKey() {
        return "tooltip.age_of_element_statue.creature_statue.empty";
    }

}
