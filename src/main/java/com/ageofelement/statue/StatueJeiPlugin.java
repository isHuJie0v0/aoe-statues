package com.ageofelement.statue;

import com.mojang.logging.LogUtils;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IIngredientAliasRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * **雕像模组自己的 JEI 集成**（2026-11-06）。
 *
 * <p>拆成独立模组后，主模组那份 {@code AoeJeiPlugin} 只覆盖 `age_of_element` 命名空间的物品，
 * 雕像现在是 `age_of_element_statue` 命名空间 ⇒ 搜 `ageofelement` / `@ageofelement` 会**漏掉雕像**。
 * 这里补两件事：</p>
 *
 * <ol>
 *   <li><b>搜索别名</b>：给雕像类物品挂 {@code ageofelement} 别名（和主模组同一套写法）；</li>
 *   <li><b>按 NBT 区分子类型</b>：Boss 雕像是一个物品 + 不同 {@code BossId}，
 *       不注册的话 JEI 会把所有 Boss 变体合并成一条。</li>
 * </ol>
 */
@JeiPlugin
public final class StatueJeiPlugin implements IModPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation UID = new ResourceLocation(StatueMod.MOD_ID, "statues");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    /** 让 JEI 按 NBT 区分「Boss 雕像」（每个 Boss 一条，才能当收集清单看）。 */
    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        try {
            registration.useNbtForSubtypes(AgeOfElementStatue.BOSS_STATUE_ITEM.get());
            LOGGER.info("[雕像] JEI：Boss 雕像已按 NBT 区分子类型");
        } catch (Throwable t) {
            LOGGER.warn("[雕像] JEI 子类型注册跳过：{}", t.toString());
        }
    }

    /** 搜索别名，让 {@code ageofelement} 也能搜到雕像。 */
    @Override
    public void registerIngredientAliases(IIngredientAliasRegistration registration) {
        try {
            List<ItemStack> stacks = new ArrayList<>();
            stacks.add(new ItemStack(AgeOfElementStatue.BOSS_STATUE_ITEM.get()));
            stacks.add(new ItemStack(AgeOfElementStatue.CREATURE_STATUE_ITEM.get()));
            // 每个 Boss 的变体也挂上（未绑定的那条 + 已绑定的那些）
            StatueTabContents.collectAll(stacks::add);
            registration.addAliases(VanillaTypes.ITEM_STACK, stacks,
                    List.of("ageofelement", "age_of_element", "age_of_element_statue"));
            LOGGER.info("[雕像] JEI：{} 件雕像已挂搜索别名", stacks.size());
        } catch (Throwable t) {
            LOGGER.warn("[雕像] JEI 搜索别名注册跳过：{}", t.toString());
        }
    }
}
