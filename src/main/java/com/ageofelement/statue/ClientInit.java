package com.ageofelement.statue;

import com.ageofelement.statue.client.BossStatueRenderer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端初始化（2026-11-06）：只注册方块实体渲染器。
 */
@Mod.EventBusSubscriber(modid = StatueMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientInit {

    private ClientInit() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AgeOfElementStatue.BOSS_STATUE_BE.get(), BossStatueRenderer::new);
    }
}
