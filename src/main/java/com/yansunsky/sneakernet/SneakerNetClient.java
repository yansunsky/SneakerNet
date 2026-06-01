package com.yansunsky.sneakernet;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * SneakerNet 客户端侧启动类 (v0.5 ECC)
 * <p>
 * Phase 1 占位符 — 目前无客户端专属逻辑，
 * 未来可能添加客户端渲染/tooltip 等。
 */
@Mod(value = SneakerNet.MOD_ID, dist = Dist.CLIENT)
public class SneakerNetClient {
    public SneakerNetClient(IEventBus modEventBus, ModContainer modContainer) {
        SneakerNet.LOGGER.info("[SneakerNet] 客户端侧已加载（ECC 模式）");
    }
}
