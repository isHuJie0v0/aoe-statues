package com.ageofelement.statue;

import com.mojang.logging.LogUtils;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import org.slf4j.Logger;

/**
 * **雕像附加模组主类**（2026-11-06）。
 *
 * <p>用户要求：「把雕像部分在开发区单独做个 jar，不需要部署进实例」——
 * 于是雕像从 `age-of-element` 里整体搬出来，独立成 {@code age_of_element_statue}。
 * **注册命名空间 = {@code age_of_element_statue}**（2026-11-06 用户要求与模组 id 一致），
 * 所以 id 形如 `age_of_element_statue:boss_statue`。</p>
 *
 * <p>搬运清单：方块/物品/方块实体注册、渲染器、命令、事件（主人加成 / 掉落 / 拍生物 / 自定义效果）、
 * 资源（blockstate/模型/贴图/语言/配方）。</p>
 */
@Mod(StatueMod.MOD_ID)
public final class StatueMod {

    public static final String MOD_ID = "age_of_element_statue";
    /** 注册用的命名空间（**故意与主模组一致**，保住存档里的 id）。 */
    public static final String REG_NS = "age_of_element_statue";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, REG_NS);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, REG_NS);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, REG_NS);
    /** 自己的创造页（和主模组 `AgeOfElementRegistry.CREATIVE_TABS` 同一套写法）。 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("statues", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.age_of_element_statue.statues"))
                    .icon(() -> new ItemStack(AgeOfElementStatue.BOSS_STATUE_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(AgeOfElementStatue.BOSS_STATUE_ITEM.get());
                        output.accept(AgeOfElementStatue.CREATURE_STATUE_ITEM.get());
                        StatueTabContents.acceptAll(output::accept);
                    })
                    .build());

    public StatueMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 先触发类初始化，再挂 DeferredRegister（顺序别改，否则条目丢失）
        AgeOfElementStatue.init();

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);


        // 事件：主人加成 / Boss 掉落 / 拍生物
        MinecraftForge.EVENT_BUS.register(StatueBonuses.class);
        MinecraftForge.EVENT_BUS.register(StatueDrops.class);
        MinecraftForge.EVENT_BUS.register(StatueCaptureEvents.class);
        MinecraftForge.EVENT_BUS.register(StatueAdjustEvents.class);   // 铁锭/金锭调大小与高度（含 shift）

        // 客户端：方块实体渲染器 + POC 命令
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 渲染器由 ClientInit 的 @Mod.EventBusSubscriber 自动注册
            MinecraftForge.EVENT_BUS.register(StatuePoc.class);   // /aoestatue 客户端命令
        }
    }
}
