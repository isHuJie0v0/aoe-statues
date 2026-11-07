package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.RegistryObject;

/**
 * **Boss 雕像的注册**（2026-11-05）：一个方块 + 一个物品 + 一个方块实体类型。
 *
 * <p>⚠️ 和 `StormBlocks` 同一个坑：这些 {@link RegistryObject} 必须在
 * {@code AgeOfElementRegistry} 把 DeferredRegister 挂到模组总线**之前**完成类初始化，
 * 所以要在 {@code register()} 开头调 {@link #init()}。</p>
 */
public final class AgeOfElementStatue {

    public static final RegistryObject<Block> BOSS_STATUE_BLOCK =
            StatueMod.BLOCKS.register("boss_statue",
                    () -> new BossStatueBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.GOLD)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .requiresCorrectToolForDrops(), true));

    /** **生物雕像**（2026-11-06 用户口径 4）：石头半砖底座、纯装饰、无光效。 */
    public static final RegistryObject<Block> CREATURE_STATUE_BLOCK =
            StatueMod.BLOCKS.register("creature_statue",
                    () -> new BossStatueBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()
                            .requiresCorrectToolForDrops(), false));

    public static final RegistryObject<Item> BOSS_STATUE_ITEM =
            StatueMod.ITEMS.register("boss_statue",
                    () -> new com.ageofelement.statue.BossStatueItem(BOSS_STATUE_BLOCK.get(), new Item.Properties()));

    /** 生物雕像的物品（无光效）。 */
    public static final RegistryObject<Item> CREATURE_STATUE_ITEM =
            StatueMod.ITEMS.register("creature_statue",
                    () -> new com.ageofelement.statue.CreatureStatueItem(CREATURE_STATUE_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<BossStatueBlockEntity>> BOSS_STATUE_BE =
            StatueMod.BLOCK_ENTITIES.register("boss_statue",
                    () -> BlockEntityType.Builder.of(BossStatueBlockEntity::new,
                            BOSS_STATUE_BLOCK.get(), CREATURE_STATUE_BLOCK.get()).build(null));

    private AgeOfElementStatue() {
    }

    /** 只用来触发类初始化（别删）。 */
    public static void init() {
        // 空方法：类加载时上面的 RegistryObject 就已经登记进 DeferredRegister
    }
}
