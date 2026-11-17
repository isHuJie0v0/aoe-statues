package com.ageofelement.statue.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **雕像 GPU 烘焙 + 几何包围盒**（2026-11-17）。
 *
 * <h3>GPU 烘焙</h3>
 * <p>顶点只在第一次上传进显存（VBO），之后每座雕像每帧只做「换模型矩阵 + 一次 draw call」，
 * CPU 侧零顶点处理。失败的模型自动回退直渲。</p>
 *
 * <h3>几何包围盒（2026-11-17 第二个改动）</h3>
 * <p>用户反馈：{@code block_factorys_bosses} 的几只 Boss 雕像**高出基座 15 格以上**。
 * 根因是原来用 GeckoLib 的 {@code visible_bounds} 当包围盒 —— 它相对的是模型 pivot，
 * 各模组渲染器还会再叠加自己的位移，两者并不等价。</p>
 * <p>现在在抓取顶点时**顺手统计真实几何的 min/max**，用它当包围盒：
 * 这才是"画出来的东西究竟占了多大空间"，与渲染器内部的平移/缩放无关。</p>
 *
 * <h3>朝向</h3>
 * <p>首次抓取时探测「这个模型吃不吃 yaw」：分别在 yaw=0 / yaw=90 抓顶点签名。</p>
 * <ul>
 *   <li>不同 ⇒ 正常模型（凋零等）：按方块朝向烘焙多份；</li>
 *   <li>相同 ⇒ 朝向来自别处（末影龙来自飞行历史）：只烘焙一份，绘制时补
 *       {@code 180° - yaw} 的旋转。</li>
 * </ul>
 */
public final class StatueGpuCache {

    /** 单个模型最多允许的批次数，防止某个变态渲染器把显存吃爆。 */
    private static final int MAX_BATCHES = 16;

    /** 抓取时把 tickCount 抬到这个值，让"出场动画"类模型进到已展开形态。 */
    private static final int SETTLE_TICK = 200;

    /**
     * 抓取前先给假实体 **空跑这么多 tick**（2026-11-17）。
     *
     * <p>为什么需要：虚空之花（BOMD）这类 Boss 的"叶子/刺/根"由 GeckoLib 的**代码动画**
     * 按实体的"生长进度"驱动，而进度是 {@code tick()} 里累加的字段 ——
     * 假实体从不 tick ⇒ 进度恒为 0 ⇒ 叶子永远不展开（用户看到"只剩花和茎"）。</p>
     *
     * <p>空跑 tick 能让这类字段推进到位。带 try/catch：个别实体 tick 会抛异常就提前停。</p>
     */
    private static final int SETTLE_TICKS = 120;

    /**
     * **候选动画时刻**（2026-11-17）。
     *
     * <p>GeckoLib 的动画进度 = {@code tickCount + partialTick}，所以"把 tickCount 设成某个值"
     * 就等于"取动画的第 N tick 帧"。虚空之花的花瓣只在 {@code spawn}/{@code blossom} 这两段动画里张开，
     * 结束后又收回 —— 固定用某个 tick 必然抓不到。</p>
     *
     * <p>所以烘焙前把这几帧都渲染一遍，**挑水平占地最大的那一帧**（花瓣张开 ⇒ 更宽），
     * 对普通模型则各帧一样，结果不变。</p>
     */
    private static final int[] SAMPLE_TICKS = {0, 4, 8, 12, 16, 24, 40, 80, 200};

    private static final Map<String, Model> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FAILED = new ConcurrentHashMap<>();

    private StatueGpuCache() {
    }

    public static int bakedCount() {
        return CACHE.size();
    }

    /**
     * 取某个模型的**几何包围盒**（模型空间，未缩放），顺便确保它已经烘焙。
     *
     * @return {宽, 高, 深, 中心X, 中心Y, 中心Z}；拿不到时返回 null（调用方回退旧的自测方式）
     */
    public static float[] boundsFor(Entity dummy, String baseKey, float facingYaw) {
        Baked baked = ensureBaked(dummy, baseKey, facingYaw);
        return baked == null ? null : sane(baked.bounds);
    }

    /** @return true = 已由本类画完；false = 放弃，调用方走直渲 */
    public static boolean render(Entity dummy, String baseKey, float facingYaw, PoseStack pose, int packedLight) {
        Baked baked = ensureBaked(dummy, baseKey, facingYaw);
        if (baked == null) {
            return false;
        }
        try {
            Model model = CACHE.get(baseKey);
            float extra = (model != null && model.yawInsensitive) ? facingYaw : Float.NaN;
            draw(baked, pose, extra);
            return true;
        } catch (Throwable t) {
            FAILED.put(baseKey, Boolean.TRUE);
            return false;
        }
    }

    /**
     * 包围盒合理性校验：正常的模型尺寸应该 < 200 格、中心偏移 < 200 格。
     * 超过就认为抓取失败（退回旧的 measure() 口径），避免雕像被平移到外太空。
     */
    private static float[] sane(float[] box) {
        if (box == null) {
            return null;
        }
        float w = box[0], h = box[1], d = box[2];
        float cx = box[3], cy = box[4], cz = box[5];
        if (!(w > 0.01F) && !(h > 0.01F) && !(d > 0.01F)) {
            return null;
        }
        if (Math.abs(w) > 200.0F || Math.abs(h) > 200.0F || Math.abs(d) > 200.0F) {
            return null;
        }
        if (Math.abs(cx) > 200.0F || Math.abs(cy) > 200.0F || Math.abs(cz) > 200.0F) {
            return null;
        }
        return box;
    }

    // ------------------------------------------------------------------ 取/烘焙

    private static Baked ensureBaked(Entity dummy, String baseKey, float facingYaw) {
        if (FAILED.containsKey(baseKey)) {
            return null;
        }
        try {
            Model model = CACHE.get(baseKey);
            if (model == null) {
                model = new Model(probeYawInsensitive(dummy));
                CACHE.put(baseKey, model);
            }
            float bakeYaw = model.yawInsensitive ? 0.0F : facingYaw;
            Baked baked = model.variants.get(bakeYaw);
            if (baked == null) {
                baked = bake(dummy, bakeYaw);
                if (baked == null || baked.batches.isEmpty()) {
                    FAILED.put(baseKey, Boolean.TRUE);
                    return null;
                }
                model.variants.put(bakeYaw, baked);
            }
            return baked;
        } catch (Throwable t) {
            FAILED.put(baseKey, Boolean.TRUE);
            return null;
        }
    }

    private static Baked bake(Entity dummy, float yaw) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        CaptureSource source = new CaptureSource();
        int oldTick = dummy.tickCount;
        float oldYaw = dummy.getYRot();
        // 2026-11-17：**必须挪到原点再抓**。
        // 否则末影龙（按位置历史定向）等模型会把"世界坐标"写进顶点，
        // 算出来的包围盒中心是 y≈91 这种世界值 → 雕像被平移飞出屏幕。
        double oldX = dummy.getX(), oldY = dummy.getY(), oldZ = dummy.getZ();
        try {
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            @SuppressWarnings("unchecked")
            EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) dispatcher.getRenderer(dummy);
            if (renderer == null) {
                return null;
            }
            dummy.tickCount = pickBestTick(dummy);
            dummy.setYRot(yaw);
            dummy.setPos(0.0D, 0.0D, 0.0D);
            renderer.render(dummy, yaw, 0.0F, new PoseStack(), source, LightTexture.FULL_BRIGHT);
            // 2026-11-17 用户：多体节方案回退（体节位置/朝向不可控，效果不如只画本体）
            return source.finish();
        } catch (Throwable t) {
            source.discard();
            return null;
        } finally {
            dummy.tickCount = oldTick;
            dummy.setYRot(oldYaw);
            dummy.setPos(oldX, oldY, oldZ);
        }
    }

    /**
     * 在 {@link #SAMPLE_TICKS} 里挑"水平占地最大"的一帧（花瓣张开的那一帧）。
     *
     * <p>返回的是 tick 值，调用方写进 {@code dummy.tickCount}。</p>
     */
    private static int pickBestTick(Entity dummy) {
        int best = SETTLE_TICK;
        float bestArea = -1.0F;
        double ox = dummy.getX(), oy = dummy.getY(), oz = dummy.getZ();
        int oldTick = dummy.tickCount;
        try {
            for (int t : SAMPLE_TICKS) {
                dummy.tickCount = t;
                dummy.setPos(0.0D, 0.0D, 0.0D);
                Footprint f = footprint(dummy);
                if (f == null) {
                    continue;
                }
                float area = f.width() * f.depth();
                if (area > bestArea) {
                    bestArea = area;
                    best = t;
                }
            }
        } catch (Throwable ignored) {
            // 采样失败就用默认帧
        } finally {
            dummy.tickCount = oldTick;
            dummy.setPos(ox, oy, oz);
        }
        return best;
    }

    /** 渲染一遍，只统计水平/垂直范围。 */
    private static Footprint footprint(Entity dummy) {
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        @SuppressWarnings("unchecked")
        EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) dispatcher.getRenderer(dummy);
        if (renderer == null) {
            return null;
        }
        SignatureConsumer consumer = new SignatureConsumer();
        MultiBufferSource source = type -> consumer;
        renderer.render(dummy, dummy.getYRot(), 0.0F, new PoseStack(), source, LightTexture.FULL_BRIGHT);
        if (consumer.count == 0) {
            return null;
        }
        return new Footprint(consumer.minX, consumer.maxX, consumer.minY, consumer.maxY,
                consumer.minZ, consumer.maxZ);
    }

    /** 渲染一遍后的范围。 */
    private record Footprint(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
        float width() {
            return maxX - minX;
        }

        float depth() {
            return maxZ - minZ;
        }
    }



    /** 让假实体空跑若干 tick，推进"生长/展开"这类内部计时字段。 */
    private static void settleTicks(Entity dummy) {
        for (int i = 0; i < SETTLE_TICKS; i++) {
            try {
                dummy.tick();
            } catch (Throwable t) {
                return;                     // 个别实体 tick 会炸：停在这里，别影响渲染
            }
        }
    }

    // ------------------------------------------------------------------ 绘制

    private static void draw(Baked baked, PoseStack pose, float extraYawOrNaN) {
        PoseStack local = new PoseStack();
        local.last().pose().set(pose.last().pose());
        local.last().normal().set(pose.last().normal());
        if (!Float.isNaN(extraYawOrNaN)) {
            // "不吃 yaw"的模型（末影龙）：绘制时补朝向；180° 是因为模型默认朝北
            local.mulPose(Axis.YP.rotationDegrees(180.0F - extraYawOrNaN));
        }

        RenderSystem.getModelViewStack().pushPose();
        try {
            RenderSystem.getModelViewStack().mulPoseMatrix(local.last().pose());
            RenderSystem.applyModelViewMatrix();
            for (Batch batch : baked.batches) {
                RenderType type = batch.type;
                type.setupRenderState();
                try {
                    ShaderInstance shader = RenderSystem.getShader();
                    if (shader == null) {
                        continue;
                    }
                    batch.buffer.bind();
                    batch.buffer.drawWithShader(RenderSystem.getModelViewMatrix(),
                            RenderSystem.getProjectionMatrix(), shader);
                    VertexBuffer.unbind();
                } finally {
                    type.clearRenderState();
                }
            }
        } finally {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    // ------------------------------------------------------------------ 探测朝向

    private static boolean probeYawInsensitive(Entity dummy) {
        float old = dummy.getYRot();
        int oldTick = dummy.tickCount;
        try {
            dummy.setPos(0.0D, 0.0D, 0.0D);
            int a = signature(dummy, 0.0F);
            int b = signature(dummy, 90.0F);
            return a == b;
        } catch (Throwable t) {
            return false;
        } finally {
            dummy.setYRot(old);
            dummy.tickCount = oldTick;
        }
    }

    private static int signature(Entity dummy, float yaw) {
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        @SuppressWarnings("unchecked")
        EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) dispatcher.getRenderer(dummy);
        if (renderer == null) {
            return 0;
        }
        SignatureConsumer consumer = new SignatureConsumer();
        MultiBufferSource source = type -> consumer;
        double ox = dummy.getX(), oy = dummy.getY(), oz = dummy.getZ();
        dummy.setYRot(yaw);
        dummy.setPos(0.0D, 0.0D, 0.0D);
        try {
            renderer.render(dummy, yaw, 0.0F, new PoseStack(), source, LightTexture.FULL_BRIGHT);
        } finally {
            dummy.setPos(ox, oy, oz);
        }
        return consumer.hash;
    }

    // ------------------------------------------------------------------ 结构

    /** 一个模型：是否吃 yaw + 各朝向的烘焙结果。 */
    private static final class Model {
        final boolean yawInsensitive;
        final Map<Float, Baked> variants = new ConcurrentHashMap<>();

        Model(boolean yawInsensitive) {
            this.yawInsensitive = yawInsensitive;
        }
    }

    private static final class Baked {
        final List<Batch> batches;
        final float[] bounds;      // {宽, 高, 深, 中心X, 中心Y, 中心Z}

        Baked(List<Batch> batches, float[] bounds) {
            this.batches = batches;
            this.bounds = bounds;
        }
    }

    private static final class Batch {
        final RenderType type;
        final VertexBuffer buffer;

        Batch(RenderType type, VertexBuffer buffer) {
            this.type = type;
            this.buffer = buffer;
        }
    }

    /** 模型空间的几何包围盒。 */
    private static final class Bounds {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        int count;

        void accept(float x, float y, float z) {
            count++;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        boolean valid() {
            return count > 0 && (maxX > minX || maxY > minY || maxZ > minZ);
        }

        float[] toArray() {
            return new float[]{maxX - minX, maxY - minY, maxZ - minZ,
                    (minX + maxX) * 0.5F, (minY + maxY) * 0.5F, (minZ + maxZ) * 0.5F};
        }
    }

    /** 抓取阶段：把顶点收进 BufferBuilder，同时统计几何包围盒。 */
    private static final class CaptureSource implements MultiBufferSource {
        private final Map<RenderType, TrackingConsumer> byType = new LinkedHashMap<>();
        private final List<RenderType> order = new ArrayList<>();
        private final Bounds bounds = new Bounds();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (type == null) {
                type = RenderType.entityCutoutNoCull(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/misc/white.png"));
            }
            TrackingConsumer existing = byType.get(type);
            if (existing != null) {
                return existing;
            }
            if (byType.size() >= MAX_BATCHES) {
                // 超过批次数上限：后续顶点丢进丢弃桶（不画），避免炸显存
                BufferBuilder discard = new BufferBuilder(256);
                discard.begin(type.mode(), type.format());
                return discard;
            }
            BufferBuilder builder = new BufferBuilder(8192);
            builder.begin(type.mode(), type.format());
            TrackingConsumer consumer = new TrackingConsumer(builder, bounds);
            byType.put(type, consumer);
            order.add(type);
            return consumer;
        }

        Baked finish() {
            List<Batch> batches = new ArrayList<>();
            for (RenderType type : order) {
                TrackingConsumer consumer = byType.get(type);
                if (consumer == null) {
                    continue;
                }
                try {
                    VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    vb.bind();
                    vb.upload(consumer.builder.end());
                    VertexBuffer.unbind();
                    batches.add(new Batch(type, vb));
                } catch (Throwable t) {
                    // 某个批次失败：丢掉它，其余继续
                }
            }
            return new Baked(batches, bounds.valid() ? bounds.toArray() : null);
        }

        void discard() {
            for (TrackingConsumer consumer : byType.values()) {
                try {
                    consumer.builder.end();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            byType.clear();
            order.clear();
        }
    }

    /** 透传给 BufferBuilder，同时把顶点坐标喂给 {@link Bounds}。 */
    private static final class TrackingConsumer implements VertexConsumer {
        final BufferBuilder builder;
        private final Bounds bounds;

        TrackingConsumer(BufferBuilder builder, Bounds bounds) {
            this.builder = builder;
            this.bounds = bounds;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            bounds.accept((float) x, (float) y, (float) z);
            return builder.vertex(x, y, z);
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return builder.color(r, g, b, a);
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return builder.uv(u, v);
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return builder.overlayCoords(u, v);
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return builder.uv2(u, v);
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return builder.normal(x, y, z);
        }

        @Override
        public void endVertex() {
            builder.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            builder.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            builder.unsetDefaultColor();
        }
    }

    /** 只算位置哈希的消费者（探测朝向用，不产生几何）。 */
    private static final class SignatureConsumer implements VertexConsumer {
        int hash = 1;
        int count;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            float fx = (float) x, fy = (float) y, fz = (float) z;
            count++;
            if (fx < minX) minX = fx;
            if (fx > maxX) maxX = fx;
            if (fy < minY) minY = fy;
            if (fy > maxY) maxY = fy;
            if (fz < minZ) minZ = fz;
            if (fz > maxZ) maxZ = fz;
            hash = hash * 31 + Float.floatToIntBits(fx);
            hash = hash * 31 + Float.floatToIntBits(fy);
            hash = hash * 31 + Float.floatToIntBits(fz);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
