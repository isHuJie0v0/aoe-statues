package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * **把「每个 Boss 都已绑定的雕像」塞进创造栏**（2026-11-06）。
 *
 * <h3>为什么要走创造栏</h3>
 * <p>JEI 的物品表不是扫注册表建的，而是**从创造栏收集**的：
 * {@code mezz.jei.library.plugins.vanilla.ingredients.ItemStackListFactory#addFromTab}
 * 会遍历所有 {@code CreativeModeTab} 的展示物品（跳掉带 `hidden_from_recipe_viewers` 标签的页签），
 * 用「物品 + 子类型 uid」去重。所以：</p>
 * <ul>
 *   <li><b>放进创造栏</b> ⇒ JEI 才会知道存在这个 NBT 变体；</li>
 *   <li>再配合 {@code AoeJeiPlugin} 里的 {@code useNbtForSubtypes(boss_statue)}
 *       ⇒ 每个 Boss 一个独立条目（否则会被当成同一件物品合并掉）。</li>
 * </ul>
 *
 * <h3>用户的目的</h3>
 * <p>「通过显示所有 boss 雕像的方式来作为玩家的收集清单」—— 于是 JEI 里搜「Boss 雕像」能看到
 * 全部条目，每条名字是「Boss 雕像 · 凋灵」这样（名字来自 {@link BossStatueItem#getName}）。</p>
 *
 * <p>名单来自 {@link BossCatalog}（标签 + 手工 extra − exclude），所以用户在
 * {@code config/age_of_element_statue/statue_bosses.json} 里加了新 Boss，这里也会跟着出现。
 * 上限 {@link #MAX_ENTRIES} 只是防止有人把名单写成几千条把创造栏/JEI 撑爆。</p>
 */
public final class StatueTabContents {

    /** 创造栏里最多放多少个雕像变体（防止名单异常膨胀）。 */
    private static final int MAX_ENTRIES = 256;

    private StatueTabContents() {
    }

    /** 给创造栏输出全部「已绑定」的雕像。任何异常都吞掉，绝不让物品栏崩。 */
    public static void acceptAll(java.util.function.Consumer<net.minecraft.world.item.ItemStack> output) {
        try {
            int n = 0;
            for (ResourceLocation id : BossCatalog.all()) {
                if (n++ >= MAX_ENTRIES) {
                    StatueMod.LOGGER.warn("[雕像] 名单超过 {} 条，创造栏只放了前 {} 条", MAX_ENTRIES, MAX_ENTRIES);
                    break;
                }
                output.accept(statueOf(id));
            }
        } catch (Throwable t) {
            StatueMod.LOGGER.warn("[雕像] 创造栏填充跳过（不影响游戏）：{}", t.toString());
        }
    }

    /** 把「未绑定的两件 + 每个 Boss 的变体」都交给回调（JEI 别名用）。 */
    public static void collectAll(java.util.function.Consumer<net.minecraft.world.item.ItemStack> sink) {
        sink.accept(new net.minecraft.world.item.ItemStack(AgeOfElementStatue.BOSS_STATUE_ITEM.get()));
        sink.accept(new net.minecraft.world.item.ItemStack(AgeOfElementStatue.CREATURE_STATUE_ITEM.get()));
        acceptAll(sink);
    }

    /** 造一个「已绑定到 id」的雕像物品（NBT 与掉落物/Boss 掉落的完全一致）。 */
    public static ItemStack statueOf(ResourceLocation id) {
        ItemStack stack = new ItemStack(AgeOfElementStatue.BOSS_STATUE_ITEM.get());
        CompoundTag be = new CompoundTag();
        be.putString("BossId", id.toString());
        stack.getOrCreateTag().put("BlockEntityTag", be);
        return stack;
    }
}
