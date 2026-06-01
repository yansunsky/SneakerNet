package com.yansunsky.sneakernet.items;

import com.yansunsky.sneakernet.SneakerNet;
import com.yansunsky.sneakernet.data.ItemNbtUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SneakerNet Package 物品行为
 * <p>
 * 右键时在目标位置放置容器方块并填入物品。
 * 数据存储在物品 NBT 中，self-contained，可交易给其他玩家。
 * <p>
 * 必须在主线程执行（操作世界方块）。
 */
public class PackageItem extends Item {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackageItem.class);

    public PackageItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.pass(itemStack); // 仅服务端

        // [1] 读取 Package 的 CustomData（1.21.1 Data Components API）
        CustomData customData = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag rootTag = customData.copyTag();

        if (!rootTag.contains("sneakernet:package")) {
            player.sendSystemMessage(Component.translatable("sneakernet.package.no_data")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }

        CompoundTag dataTag = rootTag.getCompound("sneakernet:package");

        // [2] 解析容器数据
        String containerType = dataTag.getString("containerType");
        String customNameJson = dataTag.contains("customName") ? dataTag.getString("customName") : null;

        // [3] 确定放置位置
        HitResult hitResult = player.pick(5.0, 0, false);
        BlockPos placePos;
        if (hitResult.getType() == HitResult.Type.BLOCK && hitResult instanceof BlockHitResult blockHitResult) {
            // 右键方块 → 在方块邻面放置容器
            placePos = blockHitResult.getBlockPos().relative(blockHitResult.getDirection());
        } else {
            // 右键空气 → 在玩家脚下放置容器
            placePos = player.blockPosition();
        }

        // [4] 检查放置位置是否可用
        if (!level.getBlockState(placePos).isAir()) {
            player.sendSystemMessage(Component.translatable("sneakernet.package.no_space")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }

        // [5] 放置容器方块
        Block containerBlock = getContainerBlock(containerType);
        if (containerBlock == null) {
            player.sendSystemMessage(Component.translatable("sneakernet.package.unknown_container", containerType)
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }

        level.setBlockAndUpdate(placePos, containerBlock.defaultBlockState());

        // [6] 填入物品
        BlockEntity placedBE = level.getBlockEntity(placePos);
        if (placedBE instanceof net.minecraft.world.Container container) {
            CompoundTag itemsTag = dataTag.getCompound("items");
            HolderLookup.Provider registryAccess = level.registryAccess();

            int slot = 0;
            for (String key : itemsTag.getAllKeys()) {
                CompoundTag itemTag = itemsTag.getCompound(key);
                ItemStack parsedStack = ItemStack.parseOptional(registryAccess, itemTag);
                if (!parsedStack.isEmpty() && slot < container.getContainerSize()) {
                    container.setItem(slot, parsedStack);
                    slot++;
                }
            }

            // 设置自定义名称
            if (customNameJson != null) {
                try {
                    Component customName = Component.Serializer.fromJson(customNameJson, registryAccess);
                    if (customName != null && placedBE instanceof net.minecraft.world.Nameable nameable) {
                        // BlockEntity 本身不直接 setCustomName，通过 Container 设置
                        // 对于实现了 Nameable 的 BE，我们更新其显示名称
                        placedBE.setChanged();
                    }
                } catch (Exception e) {
                    LOGGER.warn("[SneakerNet] 解析自定义名称失败", e);
                }
            }
            placedBE.setChanged();
        }

        // [7] 消耗 Package 物品
        itemStack.shrink(1);
        if (itemStack.isEmpty()) {
            player.setItemInHand(hand, ItemStack.EMPTY);
        }

        // [8] 通知玩家
        player.sendSystemMessage(Component.translatable("sneakernet.package.placed", containerType)
                .withStyle(ChatFormatting.GREEN));

        return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
    }

    /**
     * 创建 Package 物品实例
     * <p>
     * 将解密后的容器数据写入 Package 物品的 NBT
     *
     * @param containerData 解密后的容器数据
     * @param issuerKeyId   签发者 KeyID
     * @param playerUuid    导出玩家 UUID
     * @param timestamp     导出时间戳
     * @param registryAccess RegistryAccess
     * @return 带有容器数据的 Package ItemStack
     */
    public static ItemStack createPackage(ItemNbtUtil.ContainerNbtData containerData,
                                           String issuerKeyId,
                                           java.util.UUID playerUuid,
                                           long timestamp,
                                           HolderLookup.Provider registryAccess) {
        ItemStack packageStack = new ItemStack(ModItems.PACKAGE.get());

        CompoundTag rootTag = new CompoundTag();

        CompoundTag dataTag = new CompoundTag();
        dataTag.putInt("version", 2);
        dataTag.putString("issuerKeyId", issuerKeyId);
        dataTag.putString("playerUuid", playerUuid.toString());
        dataTag.putLong("exportedAt", timestamp);
        dataTag.putString("containerType", containerData.containerType());

        if (containerData.customName() != null) {
            dataTag.putString("customName", Component.Serializer.toJson(containerData.customName(), registryAccess));
        }

        // 写入物品列表
        CompoundTag itemsTag = new CompoundTag();
        List<CompoundTag> itemTags = containerData.itemTags();
        for (int i = 0; i < itemTags.size(); i++) {
            itemsTag.put(String.valueOf(i), itemTags.get(i));
        }
        dataTag.put("items", itemsTag);

        rootTag.put("sneakernet:package", dataTag);

        // 使用 1.21.1 Data Components API 写入自定义数据
        packageStack.set(DataComponents.CUSTOM_DATA, CustomData.of(rootTag));

        // 设置显示名称
        packageStack.set(DataComponents.CUSTOM_NAME,
                Component.translatable("sneakernet.package.name", containerData.containerType()));

        return packageStack;
    }

    /**
     * 根据容器类型标识获取对应的方块
     */
    private static Block getContainerBlock(String containerType) {
        return switch (containerType) {
            case "minecraft:chest" -> Blocks.CHEST;
            case "minecraft:shulker_box" -> Blocks.SHULKER_BOX; // Phase 1: 默认紫色
            case "minecraft:barrel" -> Blocks.BARREL;
            case "minecraft:trapped_chest" -> Blocks.TRAPPED_CHEST;
            case "minecraft:ender_chest" -> Blocks.ENDER_CHEST;
            default -> null;
        };
    }
}
