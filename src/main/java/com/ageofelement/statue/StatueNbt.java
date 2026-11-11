package com.ageofelement.statue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * **雕像 NBT 瘦身**（2026-11-06 首次；2026-11-07 大改）。
 *
 * <h3>为什么还要瘦身</h3>
 * <p>最早是直接把 {@code entity.saveWithoutId()} 整只存进物品：背包、装备、属性表、
 * 村民交易表 {@code Offers}、各模组 capability、AI {@code Brain} 全在里面，
 * 一条物品 NBT 能到几万字符（用户实测过）。那既有超长 NBT 风险，也是纯浪费。</p>
 *
 * <h3>为什么上一版"瘦过头"（2026-11-07 用户反馈）</h3>
 * <p>上一版是**按关键词白名单**只留 model/skin/variant/…，结果把
 * <b>装备（ArmorItems / HandItems）</b>、<b>模组自带的模型字段</b>一起丢了 ⇒
 * 僵尸雕像裸体、寄居蟹没有壳、女仆皮肤分层消失。</p>
 *
 * <h3>现在的策略：改为「黑名单 + 体积上限」</h3>
 * <ul>
 *   <li><b>默认全留</b>（保证任何模组的模型字段都在）；</li>
 *   <li>只丢掉**明确与外观无关且很占体积**的键：交易表、AI 记忆、属性表、背包、
 *       坐标/朝向/血量/无敌帧这些运行时状态；</li>
 *   <li>{@code ArmorItems} / {@code HandItems} / {@code Items} 明确**保留**
 *       （装备决定模型：盔甲、手持物、驴的箱子…）；</li>
 *   <li>单个值上限 {@link #MAX_VALUE_CHARS}，总量上限 {@link #MAX_TOTAL_CHARS}，
 *       超了先丢最大的**非保留**键。</li>
 * </ul>
 */
public final class StatueNbt {

    /** 明确丢掉：与外观无关、且通常很占体积。 */
    private static final Set<String> DROP = Set.of(
            "Offers",            // 村民交易表（最大的一项）
            "Brain",             // AI 记忆
            "Attributes",        // 属性表
            "Inventory",         // 背包（村民/驴/玩家）
            "EnderItems",        // 末影箱
            "recipeBook",        // 玩家配方书
            "Pos", "Motion", "Rotation", "UUID", "UUIDMost", "UUIDLeast",
            "Health", "AbsorptionAmount", "HurtTime", "HurtByTimestamp", "DeathTime",
            "FallDistance", "Fire", "Air", "PortalCooldown", "OnGround", "Invulnerable",
            // 2026-11-11 用户：「状态不要带例如燃烧这种，怪物被火烧死之后就会有火焰燃烧特效」
            "HasVisualFire", "RemainingFireTicks", "TicksFrozen", "IsInPowderSnow", "InPowderSnow",
            "PersistenceRequired", "CanPickUpLoot", "CanUpdate",
            "ArmorDropChances", "HandDropChances",
            "XpLevel", "XpP", "XpTotal", "XpSeed", "Score",
            "SleepingX", "SleepingY", "SleepingZ", "Sleeping",
            "Leash", "AttackTarget", "AngryAt", "AngerTime", "PatrolLeader",
            "LastRestock", "RestocksToday", "RestockDelay"
    );

    /** 即使超限也**绝不丢**的键（直接决定外观）。 */
    private static final Set<String> KEEP = Set.of(
            "ArmorItems", "HandItems", "Items", "CustomName", "CustomNameVisible",
            "Silent", "NoAI", "Glowing", "Invisible", "Sheared", "Powered", "Sitting",
            "Tame", "Owner", "CollarColor", "Color", "Variant", "Body",
            "ForgeData", "ForgeCaps"
    );

    /**
     * {@code ForgeCaps} 里**可能影响外观**的 capability（按前缀匹配），其余一律丢。
     *
     * <p>2026-11-07 用户给的样本里 ForgeCaps 有 ~30 条（cataclysm 充能、eeeabsmobs 眩晕、
     * mowziesmobs 冰冻状态、twilightforest 护盾…）全是**运行时状态**，对模型没用；
     * 真正可能影响渲染的是下面这几个。</p>
     */
    private static final String[] KEEP_CAPS = {
            "curios:", "yes_steve_model:", "goety:misc", "aether:mob_accessory", "tacz:",
            // 2026-11-11 用户：「只丢其中的 frozen 字段、保留其它」
            //   ⇒ `iceandfire:entity_data` 保留（龙的外观/阶段数据在里面），但把 frozen 字段剔掉；
            //      `mowziesmobs:frozen` 整个就是冰冻状态，直接不保留。
            "iceandfire:entity_data"
    };

    /** `iceandfire:entity_data` 里属于"冻结状态"的字段（只丢这些）。 */
    private static final String[] ICE_FIRE_FROZEN_KEYS = {
            "frozen", "frozenTicks", "isFrozen", "frost", "frostTicks"
    };

    /** {@code ForgeData} 里明确是"计数器/开关"的键前缀，直接丢。 */
    private static final String[] DROP_FORGE_DATA = {
            "flame_chase_artifacts:", "mine_fargo:", "more_mod_tetra:", "difficulty:",
            "DPTCanUpdateFixed", "elementalmobs:", "fga:", "fga.", "incontrol:",
            "apoth.", "apoth_", "BielGGSoulProvoked", "quality_tools:", "attributeslib:",
            // 2026-11-11：L2 等级/词条、Champions（含"强敌再续"）的等级/词缀
            "l2hostility:", "champions:", "champions_", "champ:",
            // 2026-11-11：元素反应的冻结/附着状态（frozen nuff、附着表）也不要
            "elementalreactions:", "nuff:", "iceandfire:"
    };

    /** 单个 NBT 值的字符上限（装备/纹理由此足够，又不会失控）。 */
    private static final int MAX_VALUE_CHARS = 4096;
    /** 整个雕像快照的字符上限（比整只实体小两个数量级）。 */
    private static final int MAX_TOTAL_CHARS = 16384;

    private StatueNbt() {
    }

    /** 把实体 NBT 压成「够还原外观、又不臃肿」的快照。 */
    public static CompoundTag compact(@Nullable CompoundTag src) {
        CompoundTag out = new CompoundTag();
        if (src == null || src.isEmpty()) {
            return out;
        }
        try {
            for (String key : src.getAllKeys()) {
                if (DROP.contains(key)) {
                    continue;
                }
                if (key.startsWith("UUID") || key.startsWith("last_") || key.equals("id")) {
                    continue;
                }
                Tag value = src.get(key);
                if (value == null) {
                    continue;
                }
                if ("ForgeCaps".equals(key) && value instanceof CompoundTag caps) {
                    CompoundTag kept = pruneCaps(caps);
                    if (!kept.isEmpty()) {
                        out.put(key, kept);
                    }
                    continue;
                }
                if ("ForgeData".equals(key) && value instanceof CompoundTag fd) {
                    CompoundTag kept = pruneForgeData(fd);
                    if (!kept.isEmpty()) {
                        out.put(key, kept);
                    }
                    continue;
                }
                if (isEmptyDeep(value)) {
                    continue;                 // BalmData:{} / champions:{affixes:[]} 这类空壳
                }
                // 2026-11-07：`MouthItem:{id:"minecraft:air", tag:{...一大坨...}}` 这种纯垃圾
                if (isAirItemStack(value)) {
                    continue;
                }
                if (("ArmorItems".equals(key) || "HandItems".equals(key) || "Items".equals(key))
                        && value instanceof net.minecraft.nbt.ListTag items) {
                    out.put(key, pruneItems(items));
                    continue;
                }
                if ("ActiveEffects".equals(key) && value instanceof net.minecraft.nbt.ListTag effects) {
                    net.minecraft.nbt.ListTag kept = pruneEffects(effects);
                    if (!kept.isEmpty()) {
                        out.put(key, kept);
                    }
                    continue;
                }
                int size = sizeOfTag(value);
                if (size > MAX_VALUE_CHARS && !KEEP.contains(key)) {
                    continue;                 // 单个值太大且不是关键字段 ⇒ 丢
                }
                out.put(key, value.copy());
            }
            trim(out);
        } catch (Throwable ignored) {
            // 压缩失败就当没有 NBT（回到默认模型）
        }
        return out;
    }

    /** 只留可能影响渲染的 capability（见 {@link #KEEP_CAPS}），空壳也丢。 */
    private static CompoundTag pruneCaps(CompoundTag caps) {
        CompoundTag kept = new CompoundTag();
        for (String id : caps.getAllKeys()) {
            if (!startsWithAny(id, KEEP_CAPS)) {
                continue;
            }
            Tag v = caps.get(id);
            if (v == null || isEmptyDeep(v)) {
                continue;
            }
            if (id.startsWith("l2hostility:traits") && v instanceof CompoundTag traits) {
                kept.put(id, pruneTraits(traits));
                continue;
            }
            if (id.startsWith("iceandfire:entity_data") && v instanceof CompoundTag ifd) {
                // 只剔除冻结相关字段，其它（species / gender / stage / variant…）保留
                CompoundTag copy = ifd.copy();
                for (String k : ICE_FIRE_FROZEN_KEYS) {
                    copy.remove(k);
                }
                if (!copy.isEmpty()) {
                    kept.put(id, copy);
                }
                continue;
            }
            kept.put(id, v.copy());
        }
        return kept;
    }

    /** 丢掉 ForgeData 里的计数器/开关（见 {@link #DROP_FORGE_DATA}）与零值。 */
    private static CompoundTag pruneForgeData(CompoundTag fd) {
        CompoundTag kept = new CompoundTag();
        for (String id : fd.getAllKeys()) {
            if (startsWithAny(id, DROP_FORGE_DATA)) {
                continue;
            }
            Tag v = fd.get(id);
            if (v == null || isEmptyTag(v) || isZeroValue(v)) {
                continue;
            }
            kept.put(id, v.copy());
        }
        return kept;
    }

    /** 递归判断"里面什么都没有"（{} / [] / 只含空壳的复合）。 */
    private static boolean isEmptyDeep(Tag tag) {
        try {
            if (tag instanceof CompoundTag c) {
                if (c.isEmpty()) {
                    return true;
                }
                for (String k : c.getAllKeys()) {
                    if (!isEmptyDeep(c.get(k))) {
                        return false;
                    }
                }
                return true;
            }
            if (tag instanceof net.minecraft.nbt.ListTag l) {
                return l.isEmpty();
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这个值是不是一个 id 为 minecraft:air 的 ItemStack（纯垃圾）。 */
    private static boolean isAirItemStack(Tag tag) {
        try {
            if (tag instanceof CompoundTag c && c.contains("id", Tag.TAG_STRING)) {
                return "minecraft:air".equals(c.getString("id"));
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 状态效果瘦身：只留 id / 等级 / 是否显示图标，丢掉 CurativeItems、Duration 等。
     * <p>留着是因为 `glowing`、`frozen` 这类会改变外观。</p>
     */
    private static net.minecraft.nbt.ListTag pruneEffects(net.minecraft.nbt.ListTag effects) {
        net.minecraft.nbt.ListTag kept = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < effects.size(); i++) {
            if (!(effects.get(i) instanceof CompoundTag e)) {
                continue;
            }
            CompoundTag slim = new CompoundTag();
            if (e.contains("forge:id", Tag.TAG_STRING)) {
                slim.putString("forge:id", e.getString("forge:id"));
            } else if (e.contains("Id")) {
                slim.putByte("Id", e.getByte("Id"));
            }
            if (e.contains("Amplifier")) {
                slim.putByte("Amplifier", e.getByte("Amplifier"));
            }
            slim.putBoolean("ShowIcon", e.getBoolean("ShowIcon"));
            if (!slim.isEmpty()) {
                kept.add(slim);
            }
        }
        return kept;
    }

    /**
     * 装备/手持物瘦身：**保留模型相关**（id / Count / Damage / Enchantments /
     * `irons_spellbooks:spell_container` 等），只削掉纯文本与词缀数据。
     *
     * <p>2026-11-07 样本里一条裤子带了 `affix_data`（含 Odin's 的 JSON 名 + uuids）~300 字符，
     * 对雕像外观毫无贡献。</p>
     */
    private static net.minecraft.nbt.ListTag pruneItems(net.minecraft.nbt.ListTag items) {
        net.minecraft.nbt.ListTag kept = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof CompoundTag item)) {
                continue;
            }
            if (isAirItemStack(item)) {
                kept.add(new CompoundTag());
                continue;                     // 空槽保持"空"（位置要对齐）
            }
            CompoundTag copy = item.copy();
            if (copy.contains("tag", Tag.TAG_COMPOUND)) {
                CompoundTag tag = copy.getCompound("tag");
                tag.remove("affix_data");
                tag.remove("display");
                tag.remove("RepairCost");
                tag.remove("HideFlags");
                if (tag.isEmpty()) {
                    copy.remove("tag");
                }
            }
            kept.add(copy);
        }
        return kept;
    }

    /** l2hostility 特性瘦身：只留 traits 表与等级（其余是战斗状态）。 */
    private static CompoundTag pruneTraits(CompoundTag traits) {
        CompoundTag slim = new CompoundTag();
        if (traits.contains("traits")) {
            slim.put("traits", traits.get("traits").copy());
        }
        if (traits.contains("lv")) {
            slim.putInt("lv", traits.getInt("lv"));
        }
        return slim;
    }

    private static boolean startsWithAny(String key, String[] prefixes) {
        for (String pre : prefixes) {
            if (key.startsWith(pre)) {
                return true;
            }
        }
        return false;
    }

    /** 空复合 / 空列表 / 空字符串。 */
    private static boolean isEmptyTag(Tag tag) {
        try {
            String str = tag.toString();
            return "{}".equals(str) || "[]".equals(str) || "\"\"".equals(str);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 0 / 0b / 0.0f / 0.0d 这种"没信息"的值。 */
    private static boolean isZeroValue(Tag tag) {
        try {
            String str = tag.toString();
            return "0".equals(str) || "0b".equals(str) || "0.0f".equals(str)
                    || "0.0d".equals(str) || "0L".equals(str) || "0s".equals(str) || "0.0".equals(str);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 超总量：按体积从大到小丢**非保留**键。 */
    private static void trim(CompoundTag out) {
        try {
            int guard = 0;
            while (totalSize(out) > MAX_TOTAL_CHARS && guard++ < 128) {
                List<String> candidates = new ArrayList<>();
                for (String key : out.getAllKeys()) {
                    if (KEEP.contains(key)) {
                        continue;
                    }
                    candidates.add(key);
                }
                if (candidates.isEmpty()) {
                    return;
                }
                candidates.sort(Comparator.comparingInt((String k) -> sizeOfTag(out.get(k))).reversed());
                out.remove(candidates.get(0));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static int totalSize(CompoundTag tag) {
        return tag.toString().length();
    }

    private static int sizeOfTag(@Nullable Tag tag) {
        if (tag == null) {
            return 0;
        }
        try {
            return tag.toString().length();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 调试用：估算字符长度。 */
    public static int sizeOf(@Nullable CompoundTag tag) {
        return tag == null ? 0 : tag.toString().length();
    }
}
