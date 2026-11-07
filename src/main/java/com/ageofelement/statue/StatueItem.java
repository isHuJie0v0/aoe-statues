package com.ageofelement.statue;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import org.jetbrains.annotations.Nullable;

/**
 * **雕像物品的公共基类**（2026-11-06 拆分）：Boss 雕像与生物雕像共用 NBT 读取与命名逻辑。
 *
 * <p>两者的区别只在子类：</p>
 * <ul>
 *   <li>{@link BossStatueItem}：**带附魔光效**（用户口径 1），掉落自 Boss；</li>
 *   <li>{@link CreatureStatueItem}：**无光效**、纯装饰（用户口径 4），可以对着生物 shift 右键"拍"下来。</li>
 * </ul>
 *
 * <p>NBT：{@code BlockEntityTag.BossId}（放下时原版 {@code BlockItem} 会原样塞进方块实体）。</p>
 */
public class StatueItem extends BlockItem {

    private static final String KEY_BE = "BlockEntityTag";
    private static final String KEY_ID = "BossId";

    public StatueItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** 从物品 NBT 里读出绑定的生物 id（没有 → null）。 */
    @Nullable
    public static ResourceLocation bossIdOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_BE)) {
            return null;
        }
        CompoundTag be = tag.getCompound(KEY_BE);
        if (!be.contains(KEY_ID)) {
            return null;
        }
        String id = be.getString(KEY_ID);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    /**
     * 写回绑定（生物雕像拍生物时用）。
     *
     * <p>⚠️ 2026-11-06 用户反馈：以前会写出 {@code {BlockEntityTag:{BossId:""}}} 这种**空值 NBT**，
     * 不同雕像的 NBT 不统一。现在**只写非空 id**；传 null/空 = 把整个 BlockEntityTag 删掉。</p>
     */
    public static void setBossId(ItemStack stack, @Nullable ResourceLocation id) {
        if (id == null || id.toString().isEmpty()) {
            clearBossId(stack);
            return;
        }
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag be = root.contains(KEY_BE) ? root.getCompound(KEY_BE) : new CompoundTag();
        be.putString(KEY_ID, id.toString());
        root.put(KEY_BE, be);
    }

    /** 读出物品里的生物 NBT 快照（模型变体用；没有 → null）。 */
    @Nullable
    public static CompoundTag entityNbtOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_BE)) {
            return null;
        }
        CompoundTag be = tag.getCompound(KEY_BE);
        return be.contains("EntityNbt") ? be.getCompound("EntityNbt").copy() : null;
    }

    /** 写入生物 NBT 快照（连同 id 一起写；null/空 = 不写）。 */
    public static void setEntityNbt(ItemStack stack, @Nullable CompoundTag nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return;
        }
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag be = root.contains(KEY_BE) ? root.getCompound(KEY_BE) : new CompoundTag();
        be.put("EntityNbt", nbt.copy());
        root.put(KEY_BE, be);
    }

    /** 清掉绑定（连同空的 BlockEntityTag 一起删，保持 NBT 干净）。 */
    public static void clearBossId(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null) {
            return;
        }
        root.remove(KEY_BE);
        if (root.isEmpty()) {
            stack.setTag(null);
        }
    }

    /** 绑定了生物：名字后面挂上生物名（JEI 收集清单靠这个区分）。 */
    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation id = bossIdOf(stack);
        if (id == null) {
            return super.getName(stack);
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) {
            return super.getName(stack).copy().append(Component.literal(" · " + id));
        }
        return super.getName(stack).copy().append(Component.literal(" · ")).append(type.getDescription());
    }

    /** 绑定目标叫什么（Boss 雕像 = "Boss"，生物雕像 = "生物"）。 */
    protected String boundWord() {
        return "Boss";
    }

    /** 空着的时候显示什么（子类覆盖；避免生物雕像上出现 "Boss" 字样）。 */
    protected String emptyKey() {
        return "tooltip.age_of_element_statue.boss_statue.empty";
    }

    /** 怎么绑定（子类覆盖）。 */
    protected String bindHintKey() {
        return "tooltip.age_of_element_statue.boss_statue.bind";
    }

    /** 绑定了之后要不要多一行金色的"荣耀"文案（只有 Boss 雕像要）。 */
    protected boolean showsGloryLine() {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, level, lines, flag);
        ResourceLocation id = bossIdOf(stack);
        if (id != null) {
            // 已绑定：只报绑的是谁（Boss 雕像再加一行金色的）
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            lines.add(Component.literal(boundWord() + "："
                            + (type == null ? id.toString() : type.getDescription().getString()))
                    .withStyle(ChatFormatting.GRAY));
            if (showsGloryLine()) {
                lines.add(Component.translatable("tooltip.age_of_element_statue.boss_statue.glory")
                        .withStyle(ChatFormatting.GOLD));
            }
        } else {
            // 空雕像：报空 + 怎么绑（用户 2026-11-06：只有空的才提示刷怪蛋绑定）
            lines.add(Component.translatable(emptyKey()).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable(bindHintKey()).withStyle(ChatFormatting.DARK_GRAY));
        }
        // 2026-11-07 用户：给雕像加上金和铁的 tooltip（怎么调大小/高度）
        lines.add(Component.translatable("tooltip.age_of_element_statue.iron_scale")
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("tooltip.age_of_element_statue.gold_height")
                .withStyle(ChatFormatting.DARK_GRAY));
        // 换底座材质（用户 2026-11-07：「还有换底座材质也要」）
        lines.add(Component.translatable("tooltip.age_of_element_statue.base_cycle")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
