package com.yansunsky.sneakernet.items;

import com.yansunsky.sneakernet.SneakerNet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * SneakerNet 创造模式物品栏注册
 * <p>
 * 为本模组注册一个独立的创造物品栏，集中放置 Ticket 与 Package，
 * 不再混入原版的创造物品栏。
 * </p>
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SneakerNet.MOD_ID);

    /**
     * SneakerNet 专属创造物品栏
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SNEAKERNET_TAB =
            CREATIVE_MODE_TABS.register("sneakernet_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.sneakernet.sneakernet_tab"))
                    // 用票据作为标签页图标
                    .icon(() -> new ItemStack(ModItems.TICKET.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.TICKET.get());
                        output.accept(ModItems.PACKAGE.get());
                    })
                    .build()
            );

    /**
     * 注册创造物品栏到事件总线
     */
    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
