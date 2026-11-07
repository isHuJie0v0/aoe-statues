package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * **生物雕像「拍生物」**（2026-11-06 用户反馈修）。
 *
 * <h3>为什么不用 {@code Item#interactLivingEntity}</h3>
 * <p>那个钩子在 {@code Player#interactOn} 里是**排在 {@code entity.interact} 之后**的：
 * 对**马/猪/船**这类可骑乘生物，右键会先被实体自己吃掉（直接上马），物品的钩子**根本轮不到**。</p>
 *
 * <p>所以改成监听 Forge 的 {@link PlayerInteractEvent.EntityInteract}（它在 {@code interactOn}
 * **最前面**触发），命中条件就 {@code cancel} 掉原版交互 —— 于是"潜行右键马"是拍雕像，而不是骑上去。</p>
 *
 * <h3>条件</h3>
 * <ul>
 *   <li>手里是生物雕像（{@link CreatureStatueItem}）；</li>
 *   <li>**潜行**；</li>
 *   <li>目标活着；</li>
 *   <li>服务端执行（客户端只放行，等服务端同步）。</li>
 * </ul>
 */
public final class StatueCaptureEvents {

    private StatueCaptureEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        try {
            Player player = event.getEntity();
            if (player == null || !player.isShiftKeyDown()) {
                return;
            }
            ItemStack stack = event.getItemStack();
            if (!(stack.getItem() instanceof CreatureStatueItem)) {
                return;
            }
            if (!(event.getTarget() instanceof LivingEntity target) || !target.isAlive()) {
                return;
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            if (id == null) {
                return;
            }
            event.setCanceled(true);          // 抢在骑乘/交易/繁殖之前
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (player.level().isClientSide) {
                return;                       // 客户端只负责"别让原版交互发生"
            }
            StatueItem.setBossId(stack, id);
            // 模型变体（女仆的模型/皮肤、村民职业…）靠实体 NBT 还原
            try {
                StatueItem.setEntityNbt(stack, StatueNbt.compact(target.saveWithoutId(new net.minecraft.nbt.CompoundTag())));
            } catch (Throwable ignored) {
                // 存不了 NBT 就只记 id
            }
            player.displayClientMessage(Component.literal("[生物雕像] 已拍下：" + id)
                    .withStyle(ChatFormatting.AQUA), true);
            StatueMod.LOGGER.debug("[生物雕像] {} 拍下 {}", player.getGameProfile().getName(), id);
        } catch (Throwable t) {
            StatueMod.LOGGER.debug("[生物雕像] 拍生物失败：{}", t.toString());
        }
    }
}
