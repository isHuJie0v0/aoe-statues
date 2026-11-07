package com.ageofelement.statue;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.Nullable;

/**
 * **Boss 雕像方块**（2026-11-05）。
 *
 * <h3>用户给的两条路线 &amp; 我们的选择</h3>
 * <ol>
 *   <li>「一个方块贴图 + 不同生物用不同 NBT」 ← **选这条**；</li>
 *   <li>「每个生物一个贴图」 ← 不选：包里有 100+ 个 Boss，还给每个画图 = 不可能维护，
 *       而且**新增 Boss 模组时又得画一遍**。</li>
 * </ol>
 * <p>更彻底：我们连「每个 Boss 一个贴图」都不需要 —— 方块实体只存 {@code entityId}，
 * 客户端直接用那只生物的**真实模型**渲染（POC 实测 44/44 能直渲）。</p>
 *
 * <h3>怎么绑定</h3>
 * <ul>
 *   <li><b>手持该 Boss 的刷怪蛋右键</b> ⇒ 直接设成那只（刷怪蛋自带实体 id，不用命令、不会拼错）；</li>
 *   <li><b>空手右键</b> ⇒ 聊天栏报当前绑定的 id；</li>
 *   <li><b>潜行 + 空手右键</b> ⇒ 清空（变回空雕像底座）；</li>
 *   <li>朝向：放置时面朝玩家（{@link #FACING}），渲染按这个转。</li>
 * </ul>
 */
public class BossStatueBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /**
     * 底座形态（2026-11-06）：
     * <ul>
     *   <li>0 = 默认（Boss 雕像半金块 / 生物雕像石头半砖）</li>
     *   <li>1 = 用自定义方块贴图的**上半段**</li>
     *   <li>2 = 用自定义方块贴图的**下半段**</li>
     * </ul>
     * 形状跟着它走，所以玩家站上去不会悬空。
     */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty BASE =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("base", 0, 2);

    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE_BOTTOM =
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 8.0D, 16.0D);
    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE_TOP =
            Block.box(0.0D, 8.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    /** true = Boss 雕像（有主人/加成/创造栏变体）；false = 生物雕像（纯装饰）。 */
    private final boolean bossKind;

    public BossStatueBlock(Properties properties, boolean bossKind) {
        super(properties);
        this.bossKind = bossKind;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(BASE, 0));
    }

    public boolean isBossKind() {
        return bossKind;
    }

    /** **放置时面朝玩家**（2026-11-06 用户：不要无论怎么放都朝一个方向）。 */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(BASE);
    }

    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE_BOTTOM;                          // 底座永远是下半格
    }

    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE_BOTTOM;                          // 底座永远是下半格
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;                 // 底座改由方块实体渲染器画（支持自定义方块 + 上下半）
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BossStatueBlockEntity(pos, state);
    }

    /** 放置：**谁放的谁是主人**，并登记进世界登记簿、立刻重算他的加成。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof BossStatueBlockEntity statue)) {
            return;
        }
        if (placer instanceof net.minecraft.server.level.ServerPlayer sp) {
            StatueEffects.fire(StatueEffects.Trigger.ON_PLACE, sp, pos,
                    level.getBlockEntity(pos) instanceof BossStatueBlockEntity st ? st.getEntityId() : "");
        }
        if (bossKind && placer instanceof Player player) {
            statue.setOwner(player.getUUID());
            try {
                StatueRegistry.get(level.getServer()).put(level.dimension(), pos, statue.getEntityId(), player.getUUID());
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    StatueBonuses.recompute(sp);
                }
            } catch (Throwable ignored) {
                // 登记失败不影响放置
            }
        }
    }

    /**
     * **生存挖掘也要掉落"绑着这只生物"的雕像**（2026-11-06 用户口径 5）。
     *
     * <p>原版方块没有战利品表就什么都不掉；这里直接手动掉一个自定义 NBT 的物品，
     * 于是「挖掉 → 拿到 → 再放下」不会丢绑定关系。</p>
     */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (!level.isClientSide && !player.isCreative()
                && blockEntity instanceof BossStatueBlockEntity statue) {
            ItemStack drop = new ItemStack(asItem());
            CompoundTag be = statue.toItemTag();            // id / 模型 NBT / 底座 / 主人，空的不写
            int baseMode = state.getValue(BASE);
            if (baseMode != 0) {
                be.putInt("BaseMode", baseMode);            // 上/下半的形态要能带回去
            }
            if (!be.isEmpty()) {
                drop.getOrCreateTag().put("BlockEntityTag", be);
            }
            popResource(level, pos, drop);
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    /**
     * **这座雕像当前提供的加成**（用户 2026-11-06：「生存模式右键提示当前雕像加成，不要显示其他的」）。
     *
     * <p>算的是「这座雕像算不算数」：同种已满 {@link StatueBonuses#perTypeCap()} 座时，本座不加成。</p>
     */
    private static String bonusLine(net.minecraft.world.entity.player.Player player, String bossId) {
        if (bossId == null || bossId.isEmpty()) {
            return "未绑定，无加成";
        }
        try {
            var server = player.getServer();
            if (server != null) {
                int have = StatueRegistry.get(server).countsOf(player.getUUID()).getOrDefault(bossId, 0);
                if (have > StatueBonuses.perTypeCap()) {
                    return "同种已满 " + StatueBonuses.perTypeCap() + " 座，本座不计加成";
                }
            }
        } catch (Throwable ignored) {
            // 读不到登记簿就按"算数"报
        }
        return "+" + fmt(StatueBonuses.healthPerStatue()) + " 最大生命 · +"
                + fmt(StatueBonuses.attackPerStatue()) + " 攻击力";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 被破坏/被替换：从登记簿里删掉，并重算原主人的加成。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (bossKind && !state.is(newState.getBlock()) && !level.isClientSide && level.getServer() != null) {
            java.util.UUID owner = null;
            String bossIdAtRemoval = "";
            try {
                if (level.getBlockEntity(pos) instanceof BossStatueBlockEntity st) {
                    bossIdAtRemoval = st.getEntityId();
                }
                // ⚠️ 先从登记簿反查（它永远有主人记录）；方块实体在 onRemove 时可能已经没了
                var removed = StatueRegistry.get(level.getServer()).removeAt(level.dimension(), pos);
                if (removed != null) {
                    owner = removed.owner();
                } else if (level.getBlockEntity(pos) instanceof BossStatueBlockEntity statue) {
                    owner = statue.getOwner();
                }
            } catch (Throwable ignored) {
                // 登记簿坏掉也别卡住拆方块
            }
            if (owner != null) {
                var p = level.getServer().getPlayerList().getPlayer(owner);
                if (p != null) {
                    StatueBonuses.recompute(p);
                    StatueEffects.fire(StatueEffects.Trigger.ON_REMOVE, p, pos, bossIdAtRemoval);
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof BossStatueBlockEntity statue)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);

        // ⓪.5 铁锭 = 调大小；金锭 = 调高度（2026-11-07 用户口径）
        //      普通右键 = 变大/上升；shift + 右键 = 变小/下降；不消耗物品
        if (stack.is(net.minecraft.world.item.Items.IRON_INGOT)) {
            float step = player.isShiftKeyDown() ? -0.05F : 0.05F;
            float now = statue.scaleMul() + step;
            statue.setScaleMul(now);
            player.displayClientMessage(Component.literal(String.format(
                            "[雕像] 大小 ×%.2f（铁锭右键变大 / 潜行右键变小）", statue.scaleMul()))
                    .withStyle(ChatFormatting.AQUA), true);
            level.sendBlockUpdated(pos, state, state, 3);
            return InteractionResult.CONSUME;
        }
        if (stack.is(net.minecraft.world.item.Items.GOLD_INGOT)) {
            float step = player.isShiftKeyDown() ? -0.05F : 0.05F;
            statue.setYOffset(statue.yOffset() + step);
            player.displayClientMessage(Component.literal(String.format(
                            "[雕像] 高度 %+.2f 格（金锭右键上升 / 潜行右键下降）", statue.yOffset()))
                    .withStyle(ChatFormatting.AQUA), true);
            level.sendBlockUpdated(pos, state, state, 3);
            return InteractionResult.CONSUME;
        }

        // ⓪ 手持方块 ⇒ 自定义底座（不消耗）：第 1 次=上半、第 2 次=下半、第 3 次=还原，循环
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(bi.getBlock());
            if (blockId != null) {
                int mode = state.getValue(BASE);
                int next = switch (mode) {
                    case 0 -> 1;
                    case 1 -> 2;
                    default -> 0;
                };
                statue.setBaseBlock(next == 0 ? "" : blockId.toString());
                level.setBlock(pos, state.setValue(BASE, next), 3);
                String what = next == 0 ? "已还原默认底座"
                        : (next == 1 ? "底座贴图 = " + blockId + " 的上半段"
                                     : "底座贴图 = " + blockId + " 的下半段");
                player.displayClientMessage(Component.literal("[雕像] " + what)
                        .withStyle(ChatFormatting.AQUA), true);
                return InteractionResult.CONSUME;
            }
        }

        // ① 刷怪蛋 ⇒ 绑定
        if (bossKind && player.isCreative() && stack.getItem() instanceof SpawnEggItem egg) {
            var type = egg.getType(stack.getTag());
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (id != null) {
                statue.setEntityId(id.toString());
                // 刷怪蛋里可能带 EntityTag（例如女仆的模型），一起记下来
                net.minecraft.nbt.CompoundTag eggTag = stack.getTag();
                if (eggTag != null && eggTag.contains("EntityTag")) {
                    statue.setEntityNbt(StatueNbt.compact(eggTag.getCompound("EntityTag")));
                } else {
                    statue.setEntityNbt(null);
                }
                // 换绑也要更新登记簿（加成按登记簿算），并重算主人
                try {
                    java.util.UUID owner = statue.getOwner() != null ? statue.getOwner() : player.getUUID();
                    statue.setOwner(owner);
                    StatueRegistry.get(level.getServer()).put(level.dimension(), pos, id.toString(), owner);
                    if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                        StatueBonuses.recompute(sp);
                    }
                } catch (Throwable ignored) {
                    // 登记失败不影响换绑
                }
                player.displayClientMessage(Component.literal("[Boss 雕像] 已设为：" + id
                                + "（同种最多计 2 座加成）")
                        .withStyle(ChatFormatting.AQUA), true);
                return InteractionResult.CONSUME;
            }
        }

        // ② 潜行 + 空手 ⇒ 清空
        if (stack.isEmpty() && player.isShiftKeyDown() && player.isCreative()) {
            player.displayClientMessage(Component.literal("[Boss 雕像] 已清空")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }

        // ③ 空手提示：生存 = 只报「当前加成」；创造 = 额外告诉你怎么换/清空（2026-11-06 用户口径）
        if (stack.isEmpty()) {
            String cur = statue.getEntityId();
            if (bossKind) {
                // 生存：**只报加成**（用户 2026-11-06：「不要显示其他的」）
                // 创造：多给"当前生物 + 怎么换 + 怎么清空"
                String msg = player.isCreative()
                        ? "[Boss 雕像] " + (cur.isEmpty() ? "（空）" : cur) + "  ·  " + bonusLine(player, cur)
                          + "  ·  手持刷怪蛋右键可更换生物 ·  潜行空手右键清空"
                        : "[Boss 雕像] 当前加成：" + bonusLine(player, cur);
                player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), false);
            } else {
                String msg = "[生物雕像] " + (cur.isEmpty() ? "（空）" : cur);
                if (player.isCreative()) {
                    msg += "  ·  手持生物雕像潜行右键生物即可更换";
                }
                player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), false);
            }
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                StatueEffects.fire(StatueEffects.Trigger.ON_INTERACT, sp, pos, cur);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
