package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * **雕像给主人的加成**（2026-11-05）：每座 **+2 最大生命 / +2 攻击力**，同种最多计 **2 座**。
 *
 * <h3>2026-11-05 晚：按性能评估 ② 改造</h3>
 * <ul>
 *   <li>座数不再「遍历该主人全部雕像」，而是直接问登记簿的**增量计数**
 *       （{@link StatueRegistry#counted(UUID)}，O(种类数)）；</li>
 *   <li>**删掉了「每 5 秒兜底」** —— 计数在放置/拆除/换绑时就准了；</li>
 *   <li>只在「放置 / 拆除 / 换绑 / 登录」这几个点重算，每次都是 O(种类数) + 两次属性写。</li>
 * </ul>
 *
 * <p>属性用固定 UUID 的 {@code ADDITION} 修饰符：幂等重算，值没变就一个包都不发。</p>
 */
public final class StatueBonuses {

    /** 每座雕像：最大生命 +2、攻击力 +2。 */
    private static final double HEALTH_PER_STATUE = 2.0D;
    private static final double ATTACK_PER_STATUE = 2.0D;

    private static final UUID HEALTH_ID = UUID.fromString("5b0f7c2e-2a91-4a1c-9d3e-7c6f1b2a8d40");
    private static final UUID ATTACK_ID = UUID.fromString("9e4c1d77-6b2f-4f8a-8a51-2d3b6c0e7f19");

    /** 每座雕像提供的最大生命。 */
    public static double healthPerStatue() {
        return HEALTH_PER_STATUE;
    }

    /** 每座雕像提供的攻击力。 */
    public static double attackPerStatue() {
        return ATTACK_PER_STATUE;
    }

    /** 同种雕像最多计几座。 */
    public static int perTypeCap() {
        return StatueRegistry.PER_TYPE_CAP;
    }

    private StatueBonuses() {
    }

    /** 有效座数（登记簿已经按类型封顶 2，这里直接取）。 */
    public static int countedStatues(ServerPlayer player) {
        try {
            return StatueRegistry.get(player.server).counted(player.getUUID());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 重算这个玩家的两个修饰符。 */
    public static void recompute(ServerPlayer player) {
        if (player == null || player.server == null) {
            return;
        }
        try {
            int n = countedStatues(player);
            apply(player.getAttribute(Attributes.MAX_HEALTH), HEALTH_ID, "aoe_statue_health", n * HEALTH_PER_STATUE);
            apply(player.getAttribute(Attributes.ATTACK_DAMAGE), ATTACK_ID, "aoe_statue_attack", n * ATTACK_PER_STATUE);
            if (player.getHealth() > player.getMaxHealth()) {
                player.setHealth(player.getMaxHealth());
            }
        } catch (Throwable t) {
            StatueMod.LOGGER.debug("[雕像] 重算加成失败：{}", t.toString());
        }
    }

    private static void apply(AttributeInstance inst, UUID id, String name, double amount) {
        if (inst == null) {
            return;
        }
        AttributeModifier old = inst.getModifier(id);
        if (amount <= 0.0D) {
            if (old != null) {
                inst.removeModifier(id);
            }
            return;
        }
        if (old != null && Math.abs(old.getAmount() - amount) < 1.0E-6D) {
            return;
        }
        if (old != null) {
            inst.removeModifier(id);
        }
        inst.addTransientModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.ADDITION));
    }

    /** 登录时补算一次（覆盖「离线期间雕像被别人拆掉」的情况）。 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            recompute(player);
        }
    }
}
