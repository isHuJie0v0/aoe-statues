package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * **Boss 掉自己的雕像**（2026-11-05 用户口径：「给 boss 接上对应雕像的掉落物」）。
 *
 * <p>判定用 {@link BossCatalog#all()}（标签 + 手工名单 − 排除），所以**名单里加了谁，谁就会掉**。
 * 掉出来的物品带 {@code BlockEntityTag.BossId}，放下时自动就是那只 Boss 的雕像
 * （原版 {@code BlockItem} 会把 {@code BlockEntityTag} 原样塞进方块实体）。</p>
 *
 * <p>只在服务端、且击杀者是玩家时掉（避免刷怪塔/自然死亡白刷雕像）。</p>
 */
public final class StatueDrops {

    private static final Set<String> BOSS_IDS = new HashSet<>();
    private static long cacheAt = -1L;

    private StatueDrops() {
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity == null || entity.level().isClientSide) {
            return;
        }
        // 玩家亲手 / 玩家的宠物·弹射物 都算（getKillCredit 覆盖这些情况）
        boolean byPlayer = event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player
                || entity.getKillCredit() instanceof net.minecraft.world.entity.player.Player;
        boolean apoth = isApothBoss(entity);
        if (!byPlayer) {
            if (apoth) {
                StatueMod.LOGGER.debug("[雕像] 神化 Boss {} 死亡，但不是玩家击杀（source={}），不掉",
                        entity.getType(), event.getSource().getEntity());
            }
            return;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null) {
            return;
        }
        // ① 名单里的 Boss；② 神化（Apotheosis）刷出来的 Boss —— 它给生物打的标记是
        //    `apoth.boss = true`（ApothBoss#createBoss 字节码实证），顺带还有 `apoth.rarity`。
        if (!isBoss(id) && !apoth) {
            return;
        }
        try {
            ItemStack stack = new ItemStack(AgeOfElementStatue.BOSS_STATUE_ITEM.get());
            CompoundTag be = new CompoundTag();
            be.putString("BossId", id.toString());
            try {
                be.put("EntityNbt", StatueNbt.compact(entity.saveWithoutId(new net.minecraft.nbt.CompoundTag())));   // 模型变体
            } catch (Throwable ignored) {
                // 存不下就只记 id
            }
            stack.getOrCreateTag().put("BlockEntityTag", be);
            StatueMod.LOGGER.debug("[雕像] {} 掉落 Boss 雕像（名单={} 神化={}）",
                    id, isBoss(id), apoth);
            ItemEntity drop = new ItemEntity(entity.level(),
                    entity.getX(), entity.getY() + 0.5D, entity.getZ(), stack);
            drop.setDefaultPickUpDelay();
            entity.level().addFreshEntity(drop);
        } catch (Throwable ignored) {
            // 掉落失败不影响死亡流程
        }
    }

    /**
     * 神化刷出来的 Boss / 精英。
     *
     * <p>字节码实证的三种标记（2026-11-06 用户反馈"神化 boss 不掉雕像"）：</p>
     * <ul>
     *   <li>{@code ApothBoss}：{@code apoth.boss = true}（另外还会写 {@code apoth.rarity}）；</li>
     *   <li>{@code ApothMiniboss}：{@code apoth.miniboss}；</li>
     *   <li>只带稀有度的精英：只有 {@code apoth.rarity}。</li>
     * </ul>
     * <p>三个都认，避免"看着是神化 Boss 但不掉"。</p>
     */
    private static boolean isApothBoss(LivingEntity entity) {
        try {
            var data = entity.getPersistentData();
            if (data.getBoolean("apoth.boss") || data.contains("apoth.miniboss")
                    || data.contains("apoth.rarity")) {
                return true;
            }
            // 兜底：任何 apoth.* 标记都当成神化产物（版本/写法差异）
            for (String k : data.getAllKeys()) {
                if (k.startsWith("apoth.") && !"apoth.no_pinata".equals(k)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBoss(ResourceLocation id) {
        long now = System.currentTimeMillis();
        if (cacheAt < 0 || now - cacheAt > 60_000L) {
            // 60 秒才重建一次（原来 5 秒，纯属浪费）
        BOSS_IDS.clear();
            for (ResourceLocation r : BossCatalog.all()) {
                BOSS_IDS.add(r.toString());
            }
            cacheAt = now;
        }
        return BOSS_IDS.contains(id.toString());
    }
}
