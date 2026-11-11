package com.ageofelement.statue;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * **雕像大小/高度调节的独立入口**（2026-11-07）。
 *
 * <h3>为什么不在 {@code Block#use} 里做</h3>
 * <p>用户反馈：「调节雕像大小的功能在 shift + 右键时不行，可能是触发的替换材质」。<br>
 * 即：潜行右键时事件链会先走"物品/方块交互"的各种判定（别的模组也常在这层拦截），
 * 我们写在 {@code use()} 里的分支不一定被调用到，于是 shift 那一半失效。</p>
 *
 * <p>这里直接在 {@link PlayerInteractEvent.RightClickBlock}（HIGHEST）里处理，
 * 命中就取消事件 ⇒ 不会再去跑底座材质替换等其它逻辑：</p>
 * <ul>
 *   <li><b>铁锭</b>：右键 = 变大（+0.05×），潜行右键 = 变小（−0.05×）；</li>
 *   <li><b>金锭</b>：右键 = 上升（+0.05 格），潜行右键 = 下降（−0.05 格）；</li>
 *   <li>不消耗物品；动作栏实时回显当前值。</li>
 * </ul>
 */
public final class StatueAdjustEvents {

    /** 铁锭：每次调整的大小步长（2026-11-11 从 0.05 放大，配合 0.1~10 的新范围）。 */
    private static final float SCALE_STEP = 0.1F;
    /** 金锭：每次调整的高度步长（格）。 */
    private static final float HEIGHT_STEP = 0.5F;

    private StatueAdjustEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        try {
            if (event.getHand() != InteractionHand.MAIN_HAND) {
                return;
            }
            Level level = event.getLevel();
            var pos = event.getPos();
            if (!(level.getBlockState(pos).getBlock() instanceof BossStatueBlock)) {
                return;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof BossStatueBlockEntity statue)) {
                return;
            }
            ItemStack stack = event.getItemStack();
            boolean iron = stack.is(Items.IRON_INGOT);
            boolean gold = stack.is(Items.GOLD_INGOT);
            if (!iron && !gold) {
                return;
            }
            // 拦下这次右键，别再走底座替换/其它模组的交互
            event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);
            event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);

            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;                                    // 客户端只负责取消
            }
            var state = level.getBlockState(pos);
            if (iron) {
                float step = player.isShiftKeyDown() ? -SCALE_STEP : SCALE_STEP;
                statue.setScaleMul(statue.scaleMul() + step);
                player.displayClientMessage(Component.literal(String.format(
                                "[雕像] 大小 ×%.2f（铁锭右键变大 / 潜行右键变小）", statue.scaleMul()))
                        .withStyle(ChatFormatting.AQUA), true);
            } else {
                float step = player.isShiftKeyDown() ? -HEIGHT_STEP : HEIGHT_STEP;
                statue.setYOffset(statue.yOffset() + step);
                player.displayClientMessage(Component.literal(String.format(
                                "[雕像] 高度 %+.2f 格（金锭右键上升 / 潜行右键下降）", statue.yOffset()))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            level.sendBlockUpdated(pos, state, state, 3);
            statue.setChanged();
        } catch (Throwable ignored) {
            // 调整失败不影响正常交互
        }
    }
}
