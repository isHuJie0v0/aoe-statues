package com.ageofelement.statue.client;

import com.ageofelement.statue.StatueMod;
import com.ageofelement.statue.BossStatueBlock;
import com.ageofelement.statue.BossStatueBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * **Boss 雕像渲染器**（2026-11-05）—— 直接把那只生物的真实模型画在方块上。
 *
 * <h3>为什么能这么做</h3>
 * <p>2026-11-03 的 POC 已经验证：{@code EntityType.create(ClientLevel)} 造一个**不进世界、不 tick**
 * 的假实体，交给 {@code Minecraft#getEntityRenderDispatcher()} 就能把**其它模组的模型**原样画出来
 * （本包 44 个 Boss 全部可直渲）。所以雕像不需要任何贴图资产，也不需要每个 Boss 一个物品。</p>
 *
 * <h3>尺寸/站位</h3>
 * <ul>
 *   <li>量尺寸优先读 **GeckoLib 的 visible_bounds**（沙虫那类"模型比碰撞箱大 30 倍"的怪才不会炸屏）；</li>
 *   <li>退路：`entity.getBoundingBoxForCulling()`，再退路是碰撞箱；</li>
 *   <li>等比缩到 {@link #TARGET_SIZE} 格、水平居中、底面贴在方块顶上；</li>
 *   <li>朝向跟着方块的 {@code FACING}（放置时面朝玩家）。</li>
 * </ul>
 *
 * <h3>找不到 id 时</h3>
 * <p>模组被删 / id 写错 ⇒ 什么都不画（方块本身是 INVISIBLE，所以会留下一个空底座），
 * <b>不抛异常、不刷屏</b>。</p>
 */
public class BossStatueRenderer implements BlockEntityRenderer<BossStatueBlockEntity> {

    /** 雕像目标高度（格）。 */
    private static final float TARGET_SIZE = 2.2F;

    /**
     * **假实体缓存**：每个方块实体只造一次（2026-11-05 晚，性能评估 ①）。
     *
     * <p>原来每帧 `type.create(level)` —— 20 座可见就是 1200 次/秒的实体构造（GeckoLib 还会建动画控制器），
     * 外加每帧 GC。现在按方块实体缓存，`entityId` 变了或方块实体卸载了才重建。</p>
     */
    private static final java.util.WeakHashMap<BossStatueBlockEntity, Cached> CACHE = new java.util.WeakHashMap<>();

    /**
     * **尺寸缓存**：按 {@link EntityType} 缓存（模型尺寸不可能变）。
     *
     * <p>量尺寸原来每帧跑一次 GeckoLib 反射（6 次 `getMethod` + 6 次 invoke），是渲染里最贵的一步；
     * 现在**一种 Boss 只量一次**，之后每帧直接查表。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<EntityType<?>, float[]> SIZE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 一个方块实体的渲染缓存。 */
    private static final class Cached {
        String id = "";
        Entity dummy;
        float[] box;                                    // {sizeX, sizeY, sizeZ, offX, offY, offZ}
    }

    public BossStatueRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BossStatueBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        BlockState baseState = be.getBlockState();
        int baseMode = baseState.hasProperty(BossStatueBlock.BASE) ? baseState.getValue(BossStatueBlock.BASE) : 0;
        // 底座永远要画（2026-11-06：以前 id 为空/类型不存在会提前 return，导致底座完全不显示）
        renderBase(be, baseMode, pose, buffers, light, overlay);

        ResourceLocation id = be.id();
        if (id == null) {
            return;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) {
            return;
        }

        // ---- 缓存命中就用旧的（每帧零构造、零反射）----
        Cached cached = CACHE.get(be);
        if (cached == null || !id.toString().equals(cached.id) || cached.dummy == null) {
            Entity fresh;
            try {
                fresh = type.create(mc.level);
            } catch (Throwable t) {
                return;
            }
            if (fresh == null) {
                return;
            }
            // 生物自己的 NBT（模型变体：女仆模型/皮肤、村民职业…）—— 先灌进去再量尺寸
            net.minecraft.nbt.CompoundTag nbt = be.getEntityNbt();
            boolean hasNbt = nbt != null && !nbt.isEmpty();
            if (hasNbt) {
                try {
                    fresh.load(nbt.copy());
                } catch (Throwable ignored) {
                    // NBT 坏了就按默认模型画
                }
            }
            cached = new Cached();
            cached.id = id.toString();
            cached.dummy = fresh;
            // 有 NBT 的变体尺寸可能和默认模型不同 ⇒ 不能按 EntityType 缓存，只能按方块实体量一次
            cached.box = hasNbt ? measure(fresh) : SIZE_CACHE.computeIfAbsent(type, t -> measure(fresh));
            CACHE.put(be, cached);
        }
        Entity dummy = cached.dummy;

        BlockState state = be.getBlockState();
        float yaw = state.hasProperty(BossStatueBlock.FACING)
                ? state.getValue(BossStatueBlock.FACING).toYRot()
                : 0.0F;
        try {
            dummy.setPos(be.getBlockPos().getX() + 0.5D, be.getBlockPos().getY(), be.getBlockPos().getZ() + 0.5D);
            dummy.setYRot(yaw);
            dummy.setXRot(0.0F);
            dummy.tickCount = 20;
            if (dummy instanceof LivingEntity living) {
                living.yBodyRot = yaw;
                living.yHeadRot = yaw;
                living.yBodyRotO = yaw;
                living.yHeadRotO = yaw;
            }
        } catch (Throwable ignored) {
            // 状态摆不好也继续画（下面有兜底量尺）
        }

        float[] box = cached.box;
        float size = Math.max(box[0], Math.max(box[1], box[2]));
        if (size <= 0.01F) {
            size = Math.max(0.1F, Math.max(dummy.getBbWidth(), dummy.getBbHeight()));
        }
        float scale = Math.max(0.002F, Math.min(6.0F, TARGET_SIZE / size));

        pose.pushPose();
        try {
            // 方块原点在 (x, y, z)，模型本体从 (0,0,0) 起画 ⇒ 先平移到方块中心、再按包围盒中心对齐
            // 底座永远是下半格 ⇒ 雕像固定站在 0.5
            // 玩家手动调的大小/高度（2026-11-07：铁锭调大小、金锭调高度）
            float manualScale = Math.max(0.2F, Math.min(4.0F, be.scaleMul()));
            float manualY = Math.max(-1.0F, Math.min(2.0F, be.yOffset()));
            pose.translate(0.5D, 0.5D + manualY, 0.5D);
            pose.translate(-box[3], -(box[4] - box[1] * 0.5F), -box[5]);
            pose.scale(scale * manualScale, scale * manualScale, scale * manualScale);
            mc.getEntityRenderDispatcher().render(dummy, 0.0D, 0.0D, 0.0D, yaw, 0.0F,
                    pose, buffers, LightTexture.FULL_BRIGHT);
        } catch (Throwable t) {
            StatueMod.LOGGER.debug("[Boss 雕像] {} 渲染失败：{}", id, t.toString());
        } finally {
            pose.popPose();
        }
    }

    /**
     * **画底座**（2026-11-06，方案 A：裁 UV，不压扁）。
     *
     * <h3>为什么不直接 scale</h3>
     * <p>直接 {@code pose.scale(1, 0.5, 1)} 再画整块模型，会把**贴图纵向压扁**（UV 还是 0~1，
     * 只是被塞进一半高度）。原版半砖不是这么干的 —— 它把侧面 UV **裁成一半**
     * （下半 `v=8..16`、上半 `v=0..8`），所以纹素密度仍是 1:1。</p>
     *
     * <p>这里就照原版的做法：遍历 {@link BakedModel} 的每个 quad，把顶点 Y 压到半格，
     * **侧面 UV 在 sprite 空间里先裁一半**，再用 {@code sprite.getU/getV} 映射到图集坐标。</p>
     *
     * <p>拿不到模型（空 quad / 异常）时退回老的「缩放整块」做法，保证底座不会消失。</p>
     */
    private static void renderBase(BossStatueBlockEntity be, int mode, PoseStack pose,
                                   MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        BlockState baseState = resolveBaseState(be, mode);
        boolean upperTex = mode == 1;      // true = 取贴图上半段，false = 下半段
        pose.pushPose();
        try {
            BakedModel model = mc.getBlockRenderer().getBlockModel(baseState);
            var random = net.minecraft.util.RandomSource.create(42L);
            // 按方块自身的渲染层取 buffer：树叶=translucent、草=cutout、石头=solid
            // （2026-11-07：以前无脑 solid，树叶/草这类会丢掉透明与颜色）
            net.minecraft.client.renderer.RenderType layer =
                    net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(baseState);
            VertexConsumer consumer = buffers.getBuffer(layer);
            boolean drew = false;
            for (Direction dir : Direction.values()) {
                for (BakedQuad quad : model.getQuads(baseState, dir, random,
                        net.minecraftforge.client.model.data.ModelData.EMPTY,
                        net.minecraft.client.renderer.RenderType.solid())) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    int[] v = quad.getVertices();
                    // 方块自带着色（草方块、树叶、模组方块的自定义 tint）——以前写死白色，所以"只有纹理没有颜色"
                    float cr = 1.0F;
                    float cg = 1.0F;
                    float cb = 1.0F;
                    int tintIndex = quad.getTintIndex();
                    if (tintIndex >= 0) {
                        try {
                            int rgb = mc.getBlockColors().getColor(baseState, mc.level,
                                    be.getBlockPos(), tintIndex);
                            if (rgb != -1) {
                                cr = ((rgb >> 16) & 0xFF) / 255.0F;
                                cg = ((rgb >> 8) & 0xFF) / 255.0F;
                                cb = (rgb & 0xFF) / 255.0F;
                            }
                        } catch (Throwable ignored) {
                            // 取不到颜色就按白色
                        }
                    }
                    // 模型里没有 tint 但方块有全局着色（例如模组方块自定义 color provider）
                    if (tintIndex < 0) {
                        try {
                            int rgb = mc.getBlockColors().getColor(baseState, mc.level,
                                    be.getBlockPos(), 0);
                            if (rgb != -1) {
                                cr = ((rgb >> 16) & 0xFF) / 255.0F;
                                cg = ((rgb >> 8) & 0xFF) / 255.0F;
                                cb = (rgb & 0xFF) / 255.0F;
                            }
                        } catch (Throwable ignored) {
                            // 同上
                        }
                    }
                    if (v.length < 32) {
                        continue;
                    }
                    // ⚠️ 2026-11-06 修：方块模型的 baked quad **坐标已经是 0~1**，
                    //    以前又除了 16 ⇒ 底座只剩一个"极小像素点"。这里按实际量纲判断：
                    //    只要这 4 个顶点里有 > 2 的坐标，才认为是 0~16 空间再除 16。
                    float scaleToBlock = quadPositionsAre16(v) ? 1.0F / 16.0F : 1.0F;
                    for (int i = 0; i < 4; i++) {
                        int o = i * 8;
                        float x = Float.intBitsToFloat(v[o]) * scaleToBlock;
                        float y = Float.intBitsToFloat(v[o + 1]) * scaleToBlock;
                        float z = Float.intBitsToFloat(v[o + 2]) * scaleToBlock;
                        // ---- UV 改成"按位置推导"，不再信任 quad 自带的 UV ----
                        // 2026-11-06 连续两次踩坑：quad 的坐标/UV 量纲在不同管线不一样，
                        // 读它就会一会儿变小点、一会儿变纯色。这里干脆自己按面算 UV。
                        float uu;
                        float vv;
                        switch (dir) {
                            case UP -> { uu = x; vv = z; }
                            case DOWN -> { uu = x; vv = 1.0F - z; }
                            case NORTH -> { uu = 1.0F - x; vv = 1.0F - y; }
                            case SOUTH -> { uu = x; vv = 1.0F - y; }
                            case WEST -> { uu = z; vv = 1.0F - y; }
                            case EAST -> { uu = 1.0F - z; vv = 1.0F - y; }
                            default -> { uu = 0.0F; vv = 0.0F; }
                        }
                        if (dir.getAxis() != Direction.Axis.Y) {
                            // 侧面：按选择取贴图上半段（0~0.5）或下半段（0.5~1）
                            vv = (upperTex ? 0.0F : 0.5F) + vv * 0.5F;
                        }
                        float su = uu * 16.0F;
                        float sv = vv * 16.0F;
                        float y2 = y * 0.5F;                       // 几何：永远占下半格
                        consumer.vertex(pose.last().pose(), x, y2, z)
                                .color(cr, cg, cb, 1.0F)
                                .uv(sprite.getU(su), sprite.getV(sv))
                                .uv2(light)
                                .normal(pose.last().normal(), dir.getStepX(), dir.getStepY(), dir.getStepZ())
                                .endVertex();
                        drew = true;
                    }
                }
            }
            if (!drew) {
                throw new IllegalStateException("no quads");
            }
        } catch (Throwable t) {
            // 兜底：老做法（整块压扁），至少看得见
            pose.pushPose();
            try {
                pose.scale(1.0F, 0.5F, 1.0F);
                mc.getBlockRenderer().renderSingleBlock(baseState, pose, buffers, light, overlay,
                        net.minecraftforge.client.model.data.ModelData.EMPTY,
                        net.minecraft.client.renderer.RenderType.solid());
            } catch (Throwable ignored) {
                // 什么都不画
            } finally {
                pose.popPose();
            }
        } finally {
            pose.popPose();
        }
    }

    /** 这个 quad 的 UV 是不是 0~1 归一化（现代 baked 模型常见），还是老式 0~16。 */
    private static boolean quadUvsAreNormalized(int[] v) {
        for (int i = 0; i < 4; i++) {
            int o = i * 8;
            for (int k = 4; k <= 5; k++) {
                if (Math.abs(Float.intBitsToFloat(v[o + k])) > 1.5F) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 这个 quad 的顶点坐标是不是 0~16 空间（老模型可能是，现代 baked 模型是 0~1）。 */
    private static boolean quadPositionsAre16(int[] v) {
        for (int i = 0; i < 4; i++) {
            int o = i * 8;
            for (int k = 0; k < 3; k++) {
                if (Math.abs(Float.intBitsToFloat(v[o + k])) > 2.0F) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 解析底座方块（2026-11-06 用户口径：「不换底座的情况下（默认情况）底座为石头和金块」）：
     * <ul>
     *   <li>{@code mode == 0}（默认）⇒ **Boss 雕像 = 金块、生物雕像 = 石头**，
     *       即使方块实体里还残留着自定义 id 也**一律忽略**；</li>
     *   <li>{@code mode == 1 / 2} ⇒ 用 {@code BaseBlock} 指的方块（取贴图上半/下半段）。</li>
     * </ul>
     */
    private static BlockState resolveBaseState(BossStatueBlockEntity be, int mode) {
        boolean bossKind = be.getBlockState().getBlock() instanceof BossStatueBlock b && b.isBossKind();
        if (mode == 0) {
            return bossKind ? net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }
        String id = be.getBaseBlock();
        if (id != null && !id.isEmpty()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    return block.defaultBlockState();
                }
            }
        }
        // mode 1/2 但 BaseBlock 丢了/无效 → 退回默认
        return bossKind ? net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState()
                : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
    }

    /**
     * 量出「模型真实大小」与「中心相对实体原点的偏移」。
     *
     * @return {@code [sizeX, sizeY, sizeZ, offX, offY, offZ]}（offY 是包围盒中心）
     */
    private static float[] measure(Entity entity) {
        // ① GeckoLib：读模型自带 visible_bounds（沙虫 = 69×55，碰撞箱只有 2 格）
        float[] gecko = measureGecko(entity);
        if (gecko != null) {
            return gecko;
        }
        // ② 原版「渲染包围盒」
        try {
            AABB b = entity.getBoundingBoxForCulling();
            return new float[]{
                    (float) b.getXsize(), (float) b.getYsize(), (float) b.getZsize(),
                    (float) (((b.minX + b.maxX) * 0.5D) - entity.getX()),
                    (float) (((b.minY + b.maxY) * 0.5D) - entity.getY()),
                    (float) (((b.minZ + b.maxZ) * 0.5D) - entity.getZ())};
        } catch (Throwable ignored) {
            // 落到碰撞箱
        }
        // ③ 碰撞箱
        Vec3 p = entity.position();
        return new float[]{entity.getBbWidth(), entity.getBbHeight(), entity.getBbWidth(), 0.0F, entity.getBbHeight() * 0.5F, 0.0F};
    }

    @Nullable
    private static float[] measureGecko(Entity entity) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Object renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null || !renderer.getClass().getName().startsWith("software.bernie.geckolib")) {
                return null;
            }
            Object geoModel = renderer.getClass().getMethod("getGeoModel").invoke(renderer);
            if (geoModel == null) {
                return null;
            }
            Object modelRes = null;
            for (var m : geoModel.getClass().getMethods()) {
                if (m.getName().equals("getModelResource") && m.getParameterCount() == 1) {
                    modelRes = m.invoke(geoModel, entity);
                    break;
                }
            }
            if (modelRes == null) {
                return null;
            }
            Object baked = null;
            for (var m : geoModel.getClass().getMethods()) {
                if (m.getName().equals("getBakedModel") && m.getParameterCount() == 1) {
                    baked = m.invoke(geoModel, modelRes);
                    break;
                }
            }
            if (baked == null) {
                return null;
            }
            Object props = baked.getClass().getMethod("properties").invoke(baked);
            if (props == null) {
                return null;
            }
            double w = (Double) props.getClass().getMethod("visibleBoundsWidth").invoke(props);
            double h = (Double) props.getClass().getMethod("visibleBoundsHeight").invoke(props);
            double[] off = (double[]) props.getClass().getMethod("visibleBoundsOffset").invoke(props);
            if (w <= 0.05D || h <= 0.05D) {
                return null;
            }
            return new float[]{(float) w, (float) h, (float) w,
                    off != null && off.length > 0 ? (float) off[0] : 0.0F,
                    off != null && off.length > 1 ? (float) off[1] : 0.0F,
                    off != null && off.length > 2 ? (float) off[2] : 0.0F};
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean shouldRenderOffScreen(BossStatueBlockEntity be) {
        return true;                                   // 大模型（龙）跨方块，别被剔除
    }
}
