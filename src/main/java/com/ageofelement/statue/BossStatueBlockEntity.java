package com.ageofelement.statue;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * **Boss 雕像的方块实体**：只存一个 {@code entityId} 字符串（2026-11-05）。
 *
 * <p>用户给了两条路线：「一个方块 + 不同 NBT」还是「每个生物一个贴图」——
 * 选前者，而且做得更彻底：**根本不需要每个 Boss 的贴图**，方块实体只记 id，
 * 客户端用 {@code EntityRenderDispatcher} 把**那只生物的真实模型**画出来
 * （POC 已实测本包 44 个 Boss 全能直渲）。这样新增 Boss 模组时，雕像自动就能用，
 * 不需要任何人画新贴图。</p>
 *
 * <p>id 为空 = 空雕像（渲染时画一个「未知造物」底座）。</p>
 */
public class BossStatueBlockEntity extends BlockEntity {

    private static final String KEY_ID = "BossId";
    private static final String KEY_OWNER = "Owner";
    /** 生物自身的 NBT（模型变体靠它，比如女仆的模型/皮肤）。 */
    private static final String KEY_ENTITY_NBT = "EntityNbt";
    /** 自定义底座方块 id（空 = 用默认：Boss 雕像半金块 / 生物雕像石头半砖）。 */
    private static final String KEY_BASE_BLOCK = "BaseBlock";
    /** 手动缩放倍率（2026-11-07：铁锭右键调大小）。 */
    private static final String KEY_SCALE = "ScaleMul";
    /** 手动高度偏移（金锭右键调高度）。 */
    private static final String KEY_Y_OFFSET = "YOffset";

    @Nullable
    private String entityId = "";
    /** 拥有者（谁放的谁是主人）；空 = 没记录（比如 /setblock 放的）。 */
    @Nullable
    private java.util.UUID owner = null;
    /** 生物的 NBT 快照（空 = 用默认模型）。 */
    @Nullable
    private CompoundTag entityNbt = null;
    /** 自定义底座方块 id（空串 = 默认底座）。 */
    @Nullable
    private String baseBlock = "";

    /** 玩家手动调的模型缩放倍率（默认 1.0 = 自动尺寸）。 */
    private float scaleMul = 1.0F;
    /** 玩家手动调的高度偏移（格，默认 0）。 */
    private float yOffset = 0.0F;

    public float scaleMul() {
        return scaleMul;
    }

    public float yOffset() {
        return yOffset;
    }

    public void setScaleMul(float v) {
        // 2026-11-11 用户：「可调节的高度和大小范围改大」——大小 0.1 ~ 10 倍
        scaleMul = Math.max(0.1F, Math.min(10.0F, v));
        setChanged();
    }

    public void setYOffset(float v) {
        // 2026-11-11 用户：有些模型在天上，差十几个方块 ⇒ 高度 -32 ~ +32 格
        yOffset = Math.max(-32.0F, Math.min(32.0F, v));
        setChanged();
    }

    public BossStatueBlockEntity(BlockPos pos, BlockState state) {
        super(AgeOfElementStatue.BOSS_STATUE_BE.get(), pos, state);
    }

    /** 存的实体 id（空串 = 没设）。 */
    public String getEntityId() {
        return entityId == null ? "" : entityId;
    }

    public void setEntityId(String id) {
        this.entityId = id == null ? "" : id;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** 自定义底座方块 id（空 = 默认）。 */
    public String getBaseBlock() {
        return baseBlock == null ? "" : baseBlock;
    }

    public void setBaseBlock(String id) {
        this.baseBlock = id == null ? "" : id;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** 生物的 NBT 快照（可能为 null）。 */
    @Nullable
    public CompoundTag getEntityNbt() {
        return entityNbt;
    }

    public void setEntityNbt(@Nullable CompoundTag nbt) {
        this.entityNbt = (nbt == null || nbt.isEmpty()) ? null : nbt.copy();
        setChanged();
    }

    /** 拥有者 UUID（可能为 null）。 */
    @Nullable
    public java.util.UUID getOwner() {
        return owner;
    }

    public void setOwner(@Nullable java.util.UUID owner) {
        this.owner = owner;
        setChanged();
    }

    /** 解析成 {@link ResourceLocation}（空/非法 → null）。 */
    @Nullable
    public ResourceLocation id() {
        String s = getEntityId();
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(KEY_ID, getEntityId());
        if (owner != null) {
            tag.putString(KEY_OWNER, owner.toString());
        }
        if (entityNbt != null && !entityNbt.isEmpty()) {
            tag.put(KEY_ENTITY_NBT, entityNbt.copy());
        }
        if (baseBlock != null && !baseBlock.isEmpty()) {
            tag.putString(KEY_BASE_BLOCK, baseBlock);
        }
        tag.putFloat(KEY_SCALE, scaleMul);
        tag.putFloat(KEY_Y_OFFSET, yOffset);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        entityId = tag.getString(KEY_ID);
        baseBlock = tag.getString(KEY_BASE_BLOCK);
        scaleMul = tag.contains(KEY_SCALE) ? tag.getFloat(KEY_SCALE) : 1.0F;
        yOffset = tag.contains(KEY_Y_OFFSET) ? tag.getFloat(KEY_Y_OFFSET) : 0.0F;
        try {
            entityNbt = tag.contains(KEY_ENTITY_NBT) ? tag.getCompound(KEY_ENTITY_NBT).copy() : null;
        } catch (Throwable ignored) {
            entityNbt = null;
        }
        try {
            owner = tag.contains(KEY_OWNER) ? java.util.UUID.fromString(tag.getString(KEY_OWNER)) : null;
        } catch (Throwable ignored) {
            owner = null;
        }
    }

    /** 打包成物品的 {@code BlockEntityTag}（空字段不写，保持 NBT 干净）。 */
    public CompoundTag toItemTag() {
        CompoundTag out = new CompoundTag();
        if (entityId != null && !entityId.isEmpty()) {
            out.putString(KEY_ID, entityId);
        }
        if (entityNbt != null && !entityNbt.isEmpty()) {
            out.put(KEY_ENTITY_NBT, entityNbt.copy());
        }
        if (baseBlock != null && !baseBlock.isEmpty()) {
            out.putString(KEY_BASE_BLOCK, baseBlock);
        }
        if (owner != null) {
            out.putString(KEY_OWNER, owner.toString());
        }
        out.putFloat(KEY_SCALE, scaleMul);
        out.putFloat(KEY_Y_OFFSET, yOffset);
        return out;
    }

    /** 大模型（龙那类）会跨出方块，渲染剔除盒给大一点（原版的钩子在 BlockEntity 上）。 */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition).inflate(4.0D);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
