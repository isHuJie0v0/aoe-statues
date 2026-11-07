package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **Boss 雕像 · POC（验证「能不能直接借用别的模组的模型」）**（2026-11-03）。
 *
 * <h3>它做什么</h3>
 * <ol>
 *   <li>枚举全部 <b>boss</b>：注册表 + {@code #forge:bosses} / {@code #c:bosses} / {@code #neoforge:bosses} 标签
 *       （实测本包并起来 44 条；真·正式版还要叠数据包/KJS 白名单）；</li>
 *   <li>对每个 id：{@code EntityType.create(level)} 造一个<b>客户端假实体</b>（不进世界、不 tick），
 *       然后交给 {@code Minecraft#getEntityRenderDispatcher()} <b>直接重放对方的 renderer</b>；</li>
 *   <li>把结果排成一面墙画在玩家面前，然后统计：<b>多少能直渲 / 多少必须兜底</b>，以及失败原因（每个 id 一条）。</li>
 * </ol>
 *
 * <h3>为什么要先做 POC</h3>
 * <p>「能不能拿到模型」是一回事，「第三方 renderer 有没有隐含前提」是另一回事：
 * 多部件 boss（末影龙/凋灵）、GeckoLib/AzureLib 的动画缓存、少数 renderer 会读 {@code BossEvent} 或自定义状态，
 * 都可能在 {@code render()} 里炸。这个 POC 就是<b>用真实数据把这批坑数出来</b>，
 * 再决定正式版是「全自动」还是「全自动 + 黑名单 + 通用雕像兜底」。</p>
 *
 * <h3>用法</h3>
 * <pre>
 *   /aoestatue scan     在玩家前方摆出全部 boss 的试渲染（约 3 秒后聊天栏给报告）
 *   /aoestatue clear    撤掉
 * </pre>
 *
 * <p>只在客户端加载（{@code Dist.CLIENT}）；服务端完全不知道这件事。</p>
 */
@Mod.EventBusSubscriber(modid = StatueMod.MOD_ID, value = Dist.CLIENT)
public final class StatuePoc {

    /** 一面墙排几列。 */
    private static final int COLS = 8;
    /** 间距（格）。 */
    private static final double SPACING = 3.0D;
    /** 雕像基准尺寸（格）：按实体包围盒等比缩放到这个大小。 */
    private static final float TARGET_SIZE = 2.2F;

    /** 一个试渲染槽位。 */
    private static final class Slot {
        final ResourceLocation id;
        final Entity dummy;
        final Vec3 pos;
        /** 0 = 还没渲过；1 = 成功；-1 = 抛异常（之后不再尝试）。 */
        int status = 0;
        String error = "";
        /**
         * 缩放分母（= 模型真实包围盒的最大边长）。
         *
         * <p>⚠️ 不能用 {@code getBbWidth()/getBbHeight()}：那是**碰撞箱**，和模型大小可以差一个数量级。
         * 例：`block_factorys_bosses:sandworm`（斯洛克，巨型沙虫）是 GeckoLib 模型，
         * geo 里 `visible_bounds_width = 69`、`visible_bounds_offset = [0,-7.5,0]`，
         * 而碰撞箱只有 2 格左右 ⇒ 按碰撞箱缩放会把模型放大三十多倍（用户 2026-11-03 实测「体型异常大」）。
         * 所以改用 {@code renderer.getBoundingBoxForCulling(entity)}（GeckoLib 会按 visible_bounds 返回），
         * 拿不到才退回碰撞箱。</p>
         */
        float sizeX = -1.0F;
        float sizeY = -1.0F;
        float sizeZ = -1.0F;
        /** 模型在实体局部坐标里的偏移（让模型居中、并站在地面上）。 */
        float offX = 0.0F;
        float offY = 0.0F;
        float offZ = 0.0F;

        Slot(ResourceLocation id, Entity dummy, Vec3 pos) {
            this.id = id;
            this.dummy = dummy;
            this.pos = pos;
        }
    }

    private static final List<Slot> SLOTS = new ArrayList<>();
    /** 扫描后第几帧出报告（-1 = 还没排）。 */
    private static int reportIn = -1;

    private StatuePoc() {
    }

    // ------------------------------------------------------------------ 命令

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        var root = net.minecraft.commands.Commands.literal("aoestatue");

        root.then(net.minecraft.commands.Commands.literal("scan").executes(ctx -> {
            scan();
            return 1;
        }));
        root.then(net.minecraft.commands.Commands.literal("clear").executes(ctx -> {
            clear(true);
            return 1;
        }));
        // ---- Boss 名单（2026-11-05 用户：「标签不全，加个可以后期加的名单」）----
        root.then(net.minecraft.commands.Commands.literal("list").executes(ctx -> {
            list();
            return 1;
        }));
        root.then(net.minecraft.commands.Commands.literal("candidates").executes(ctx -> {
            candidates();
            return 1;
        }));
        root.then(net.minecraft.commands.Commands.literal("reloadfx").executes(ctx -> {
            StatueEffects.reload();
            say("[雕像] 自定义效果配置已重读");
            return 1;
        }));
        root.then(net.minecraft.commands.Commands.literal("reload").executes(ctx -> {
            BossCatalog.reload();
            say("[雕像] 名单已重读：" + BossCatalog.all().size() + " 条");
            return 1;
        }));
        root.then(net.minecraft.commands.Commands.literal("add")
                .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id").trim();
                            boolean ok = BossCatalog.add(id);
                            say(ok ? "[雕像] 已加入名单：" + id + "（共 " + BossCatalog.all().size() + " 条）"
                                    : "[雕像] 加不进去：" + id + "（id 不存在，或本来就在名单里）");
                            return 1;
                        })));
        root.then(net.minecraft.commands.Commands.literal("remove")
                .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id").trim();
                            boolean ok = BossCatalog.remove(id);
                            say(ok ? "[雕像] 已从名单移除：" + id + "（剩 " + BossCatalog.all().size() + " 条）"
                                    : "[雕像] 没找到：" + id);
                            return 1;
                        })));

        event.getDispatcher().register(root);
    }

    // ------------------------------------------------------------------ 扫描

    /** 枚举所有 boss id：注册表 + 三个 bosses 标签。 */
    /**
     * 最终 Boss 名单 —— 现在交给 {@link BossCatalog}（标签 + 手工名单 - 排除）。
     */
    public static List<ResourceLocation> collectBossIds() {
        return BossCatalog.all();
    }

    private static boolean isBossTagged(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        for (String ns : new String[]{"forge", "c", "neoforge"}) {
            TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(ns, "bosses"));
            try {
                if (type.is(tag)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 标签加载异常不该影响枚举
            }
        }
        return false;
    }

    private static void scan() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        clear(false);
        List<ResourceLocation> ids = collectBossIds();

        // 以玩家视线前方为起点排一面墙
        Vec3 eye = mc.player.position();
        float yaw = mc.player.getYRot();
        double dirX = -Math.sin(Math.toRadians(yaw));
        double dirZ = Math.cos(Math.toRadians(yaw));
        Vec3 origin = eye.add(dirX * 5.0D, 0.0D, dirZ * 5.0D);
        double perpX = -dirZ;
        double perpZ = dirX;

        int made = 0;
        for (int i = 0; i < ids.size(); i++) {
            ResourceLocation id = ids.get(i);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            if (type == null) {
                continue;
            }
            Entity dummy;
            try {
                dummy = type.create(mc.level);
            } catch (Throwable t) {
                StatueMod.LOGGER.warn("[雕像POC] {} 创建失败：{}", id, t.toString());
                continue;
            }
            if (dummy == null) {
                StatueMod.LOGGER.warn("[雕像POC] {} create() 返回 null（该实体可能不允许客户端创建）", id);
                continue;
            }
            int col = i % COLS;
            int row = i / COLS;
            double x = origin.x + perpX * (col - (COLS - 1) / 2.0D) * SPACING;
            double z = origin.z + perpZ * (col - (COLS - 1) / 2.0D) * SPACING - dirZ * row * SPACING;
            double y = mc.player.getY();

            try {
                dummy.setPos(x, y, z);
                dummy.setYRot(yaw + 180.0F);
                dummy.setXRot(0.0F);
                dummy.tickCount = 20;
                if (dummy instanceof LivingEntity living) {
                    living.yBodyRot = yaw + 180.0F;
                    living.yHeadRot = yaw + 180.0F;
                    living.yBodyRotO = living.yBodyRot;
                    living.yHeadRotO = living.yHeadRot;
                    living.setHealth(living.getMaxHealth());
                }
            } catch (Throwable ignored) {
                // 假实体的状态设置失败也没关系，渲染时会兜底
            }
            Slot slot = new Slot(id, dummy, new Vec3(x, y, z));
            measure(slot);
            SLOTS.add(slot);
            made++;
        }
        reportIn = 60;   // 3 秒后出报告（给渲染线程足够时间跑完一轮）
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[雕像POC] 开始试渲染 " + made + " 个 boss…")
                    .withStyle(ChatFormatting.AQUA), false);
        }
        StatueMod.LOGGER.info("[雕像POC] 开始试渲染 {} 个 boss（候选 {} 个）", made, ids.size());
    }

    /**
     * **量一下这个模型真实有多大**（用户 2026-11-03：「有一个体型异常大，是 boss rise 的沙虫」）。
     *
     * <h3>为什么不能用碰撞箱</h3>
     * <p>`getBbWidth()/getBbHeight()` 是**碰撞箱**，和模型可以差一个数量级。
     * 沙虫 `block_factorys_bosses:sandworm` 的 geo 模型里写的是
     * `visible_bounds_width = 69` / `visible_bounds_height = 55` / `offset = [0, -7.5, 0]`，
     * 而碰撞箱只有 2 格左右 —— 按碰撞箱缩放会把模型放大三十多倍，正是那次「莫名巨大」。</p>
     *
     * <h3>怎么量才准</h3>
     * <ol>
     *   <li><b>GeckoLib 实体</b>：反射走 {@code renderer.getGeoModel()} → {@code getModelResource(entity)}
     *       → {@code getBakedModel(loc)} → {@code properties()}，直接读模型自带的
     *       {@code visibleBoundsWidth/Height/Offset}（这是作者自己声明的「这只怪占多大」）；</li>
     *   <li>量不到就退回 {@code entity.getBoundingBoxForCulling()}；</li>
     *   <li>再不行退回碰撞箱（渲染时还有一层兜底）。</li>
     * </ol>
     * <p>全程反射 + {@code catch(Throwable)}：GeckoLib 不在、API 变了，都只会退回上一档，不会崩。</p>
     */
    private static void measure(Slot slot) {
        if (measureGecko(slot)) {
            return;
        }
        try {
            // 1.20.1 的「渲染包围盒」是实体自己的方法（EntityRenderer 上没有）；
            // 原版实体基本等于碰撞箱，个别模组会覆写成更大的盒子。
            net.minecraft.world.phys.AABB box = slot.dummy.getBoundingBoxForCulling();
            applyBox(slot, box.getXsize(), box.getYsize(), box.getZsize(),
                    ((box.minX + box.maxX) * 0.5D) - slot.dummy.getX(),
                    ((box.minY + box.maxY) * 0.5D) - slot.dummy.getY(),
                    ((box.minZ + box.maxZ) * 0.5D) - slot.dummy.getZ());
            StatueMod.LOGGER.debug("[雕像POC] {} 用渲染包围盒 {}×{}×{}", slot.id,
                    String.format("%.1f", slot.sizeX), String.format("%.1f", slot.sizeY), String.format("%.1f", slot.sizeZ));
        } catch (Throwable t) {
            StatueMod.LOGGER.debug("[雕像POC] {} 量尺寸失败，退回碰撞箱：{}", slot.id, t.toString());
        }
    }

    /** GeckoLib：读模型自带 visible_bounds（反射，失败返回 false 让调用方换下一档）。 */
    private static boolean measureGecko(Slot slot) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Object renderer = mc.getEntityRenderDispatcher().getRenderer(slot.dummy);
            if (renderer == null || !renderer.getClass().getName().startsWith("software.bernie.geckolib")) {
                return false;
            }
            Object geoModel = renderer.getClass().getMethod("getGeoModel").invoke(renderer);
            if (geoModel == null) {
                return false;
            }
            // getModelResource(T animatable) 的形参被擦成 GeoAnimatable ⇒ 按名字找，再传实体
            Object modelRes = null;
            for (java.lang.reflect.Method m : geoModel.getClass().getMethods()) {
                if (!m.getName().equals("getModelResource") || m.getParameterCount() != 1) {
                    continue;
                }
                modelRes = m.invoke(geoModel, slot.dummy);
                break;
            }
            if (modelRes == null) {
                return false;
            }
            Object baked = null;
            for (java.lang.reflect.Method m : geoModel.getClass().getMethods()) {
                if (m.getName().equals("getBakedModel") && m.getParameterCount() == 1) {
                    baked = m.invoke(geoModel, modelRes);
                    break;
                }
            }
            if (baked == null) {
                return false;
            }
            Object props = baked.getClass().getMethod("properties").invoke(baked);
            if (props == null) {
                return false;
            }
            double w = (Double) props.getClass().getMethod("visibleBoundsWidth").invoke(props);
            double h = (Double) props.getClass().getMethod("visibleBoundsHeight").invoke(props);
            double[] off = (double[]) props.getClass().getMethod("visibleBoundsOffset").invoke(props);
            if (w <= 0.05D || h <= 0.05D) {
                return false;
            }
            applyBox(slot, w, h, w,
                    off != null && off.length > 0 ? off[0] : 0.0D,
                    off != null && off.length > 1 ? off[1] : 0.0D,
                    off != null && off.length > 2 ? off[2] : 0.0D);
            StatueMod.LOGGER.debug("[雕像POC] {} 用 GeckoLib visible_bounds {}×{}（offset {}）", slot.id,
                    String.format("%.1f", w), String.format("%.1f", h),
                    off == null ? "null" : String.format("%.1f/%.1f/%.1f", off[0], off[1], off[2]));
            return true;
        } catch (Throwable t) {
            StatueMod.LOGGER.debug("[雕像POC] {} GeckoLib 量尺寸失败：{}", slot.id, t.toString());
            return false;
        }
    }

    /**
     * 记下尺寸与「包围盒中心相对实体原点」的偏移。
     *
     * <p>传入的是<b>中心</b>坐标（{@code visible_bounds_offset} 与 AABB 中心都是中心语义），
     * 渲染时把中心移到落点、再把底面压到地面（{@code offY - sizeY/2}）。</p>
     */
    private static void applyBox(Slot slot, double sx, double sy, double sz, double cx, double cy, double cz) {
        if (sx <= 0.01D && sy <= 0.01D && sz <= 0.01D) {
            return;
        }
        slot.sizeX = (float) sx;
        slot.sizeY = (float) sy;
        slot.sizeZ = (float) sz;
        slot.offX = (float) cx;
        slot.offY = (float) cy;
        slot.offZ = (float) cz;
    }

    private static void clear(boolean message) {
        SLOTS.clear();
        reportIn = -1;
        if (message) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[雕像POC] 已撤掉").withStyle(ChatFormatting.GRAY), false);
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || SLOTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        try {
            for (Slot slot : SLOTS) {
                if (slot.status < 0) {
                    continue;                       // 已经炸过的不再尝试
                }
                pose.pushPose();
                try {
                    pose.translate(slot.pos.x - cam.x, slot.pos.y - cam.y, slot.pos.z - cam.z);
                    // 用「模型真实包围盒」（不是碰撞箱）等比缩放到 TARGET_SIZE，并让模型居中、站在地面上
                    float size = Math.max(slot.sizeX, Math.max(slot.sizeY, slot.sizeZ));
                    if (size <= 0.01F) {
                        size = Math.max(0.1F, Math.max(slot.dummy.getBbWidth(), slot.dummy.getBbHeight()));
                    }
                    float scale = TARGET_SIZE / size;
                    scale = Math.max(0.002F, Math.min(6.0F, scale));
                    // 模型包围盒中心 → 落点；再把「中心到地面」的半个高度补回去 ⇒ 底面贴地、水平居中
                    pose.translate(-slot.offX, -(slot.offY - slot.sizeY * 0.5F), -slot.offZ);
                    pose.scale(scale, scale, scale);
                    mc.getEntityRenderDispatcher().render(slot.dummy, 0.0D, 0.0D, 0.0D,
                            slot.dummy.getYRot(), 0.0F, pose, buffers, LightTexture.FULL_BRIGHT);
                    if (slot.status == 0) {
                        slot.status = 1;
                    }
                } catch (Throwable t) {
                    slot.status = -1;
                    slot.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                    StatueMod.LOGGER.warn("[雕像POC] {} 渲染失败：{}", slot.id,
                            slot.error == null ? t.toString() : slot.error);
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            try {
                buffers.endBatch();
            } catch (Throwable ignored) {
                // 收尾失败也不该把这一帧带走
            }
        }
    }

    // ------------------------------------------------------------------ 报告

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || reportIn < 0) {
            return;
        }
        if (--reportIn > 0) {
            return;
        }
        reportIn = -1;
        report();
    }

    /** 聊天栏一行（本地命令的小工具）。 */
    private static void say(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA), false);
        }
        StatueMod.LOGGER.info(msg);
    }

    /** `/aoestatue list`：把名单打成几行（含来源统计）。 */
    private static void list() {
        List<ResourceLocation> all = BossCatalog.all();
        say("[雕像] 名单共 " + all.size() + " 条（标签 " + BossCatalog.taggedCount()
                + " + 手工 " + BossCatalog.manualExtra().size()
                + " - 排除 " + BossCatalog.manualExclude().size() + "）");
        say("[雕像] 名单文件：" + BossCatalog.filePath());
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ResourceLocation id : all) {
            sb.append(id).append("  ");
            if (++shown % 4 == 0) {
                say("  " + sb);
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            say("  " + sb);
        }
        if (all.size() > shown) {
            say("  …其余见上");
        }
    }

    /** `/aoestatue candidates`：名字像 Boss、但还没在名单里的（给用户挑）。 */
    private static void candidates() {
        List<ResourceLocation> cand = BossCatalog.candidates();
        if (cand.isEmpty()) {
            say("[雕像] 没有疑似漏收的候选（关键词：boss/king/warden/dragon/wither/elder/titan…）");
            return;
        }
        say("[雕像] 疑似漏收 " + cand.size() + " 条 —— 眼熟的用 /aoestatue add <id> 加进名单：");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ResourceLocation id : cand) {
            sb.append(id).append("  ");
            if (++shown % 3 == 0) {
                say("  " + sb);
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            say("  " + sb);
        }
    }

    private static void report() {
        Minecraft mc = Minecraft.getInstance();
        int ok = 0;
        int fail = 0;
        int pending = 0;
        List<String> failList = new ArrayList<>();
        for (Slot slot : SLOTS) {
            if (slot.status > 0) {
                ok++;
            } else if (slot.status < 0) {
                fail++;
                if (failList.size() < 8) {
                    failList.add(slot.id + " → " + slot.error);
                }
            } else {
                pending++;
            }
        }
        String line = "[雕像POC] 可直渲 " + ok + " / 失败 " + fail + " / 未出帧 " + pending + "（共 " + SLOTS.size() + "）";
        StatueMod.LOGGER.info(line);
        for (Slot slot : SLOTS) {
            StatueMod.LOGGER.info("[雕像POC]   {} = {}", slot.id,
                    slot.status > 0 ? "OK" : (slot.status < 0 ? "FAIL " + slot.error : "未出帧"));
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(line)
                    .withStyle(fail == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
            for (String f : failList) {
                mc.player.displayClientMessage(Component.literal("  " + f).withStyle(ChatFormatting.RED), false);
            }
            if (fail > failList.size()) {
                mc.player.displayClientMessage(
                        Component.literal("  …其余 " + (fail - failList.size()) + " 个见 logs/latest.log")
                                .withStyle(ChatFormatting.GRAY), false);
            }
        }
    }
}
