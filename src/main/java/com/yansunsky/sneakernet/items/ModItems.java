package com.yansunsky.sneakernet.items;

import com.yansunsky.sneakernet.SneakerNet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * SneakerNet 物品注册
 * <p>
 * 注册两种物品：
 * <ul>
 *   <li>sneakernet_ticket — 票据物品，右键容器导出凭证（可合成）</li>
 *   <li>sneakernet_package — 包裹物品，右键放置容器（不可合成，import 后获得）</li>
 * </ul>
 */
public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(SneakerNet.MOD_ID);

    // ─── SneakerNet Ticket（票据）───
    // 可合成：8纸+1墨囊→8个，右键容器导出
    public static final DeferredItem<TicketItem> TICKET = ITEMS.register(
            "sneakernet_ticket",
            () -> new TicketItem(new Item.Properties()
                    .stacksTo(64)
                    .rarity(Rarity.UNCOMMON)
            )
    );

    // ─── SneakerNet Package（包裹）───
    // 不可合成，/sneakernet import 后获得，右键放置容器
    public static final DeferredItem<PackageItem> PACKAGE = ITEMS.register(
            "sneakernet_package",
            () -> new PackageItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.RARE)
            )
    );

    /**
     * 注册物品到事件总线
     */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
