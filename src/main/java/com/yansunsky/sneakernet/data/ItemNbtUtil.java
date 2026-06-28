package com.yansunsky.sneakernet.data;

import com.yansunsky.sneakernet.SneakerNet;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器 NBT 序列化工具
 * <p>
 * 使用 FriendlyByteBuf 将容器 NBT 序列化为二进制（用于加密），
 * 以及从二进制还原容器数据。
 * </p>
 *
 * <pre>
 * 二进制格式:
 *   [containerType UTF]     — 容器类型 (ResourceLocation 字符串)
 *   [hasCustomName bool]    — 是否有自定义名称
 *   [customName UTF]        — 自定义名称 JSON (仅当 hasCustomName=true)
 *   [itemCount varInt]      — 非空物品槽数量
 *   [slotIndex varInt]      — 物品所在槽位
 *   [itemNbt NBT]           — 物品 CompoundTag (通过 writeNbt/readNbt)
 *   ... (重复 slotIndex + itemNbt)
 * </pre>
 */
public final class ItemNbtUtil {

    private ItemNbtUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 容器反序列化结果
     *
     * @param containerType 容器类型（如 "minecraft:chest"）
     * @param customName    自定义名称（可为 null）
     * @param itemTags      物品 NBT 标签列表
     */
    public record ContainerNbtData(
            String containerType,
            Component customName,
            List<CompoundTag> itemTags
    ) {}

    // ======================== 序列化 ========================

    /**
     * 将容器序列化为二进制字节（用于加密）
     * <p>
     * 将容器的类型、自定义名称、所有非空物品序列化为二进制格式。
     * 需要传入 RegistryAccess 用于物品 NBT 保存。
     * </p>
     *
     * @param container     容器对象
     * @param registryAccess 注册表访问器（用于 ItemStack.save）
     * @return 二进制数据
     */
    public static byte[] serializeContainerToBytes(Container container, RegistryAccess registryAccess) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 1. 写入容器类型
            String containerType = getContainerType(container);
            buf.writeUtf(containerType);

            // 2. 写入自定义名称
            Component customName = getContainerCustomName(container);
            if (customName != null) {
                buf.writeBoolean(true);
                // 使用 Component.Serializer 将名称序列化为 JSON 字符串
                buf.writeUtf(Component.Serializer.toJson(customName, registryAccess));
            } else {
                buf.writeBoolean(false);
            }

            // 3. 写入物品列表
            List<CompoundTag> items = new ArrayList<>();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    items.add(serializeItemStack(stack, registryAccess));
                }
            }

            buf.writeVarInt(items.size());
            for (CompoundTag itemTag : items) {
                buf.writeNbt(itemTag);
            }

            // 4. 读取结果
            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return result;
        } finally {
            buf.release();
        }
    }

    /**
     * 将 IItemHandler 序列化为二进制字节（用于加密，兼容任意容器）
     * <p>
     * 与 {@link #serializeContainerToBytes} 使用完全相同的二进制格式，
     * 但物品来源是 NeoForge 的 {@link IItemHandler} 能力，从而支持
     * 原版容器以及绝大多数暴露物品能力的模组容器。
     * </p>
     *
     * @param handler        物品能力处理器
     * @param containerType  容器类型（方块注册名，如 "minecraft:chest"）
     * @param customName     自定义名称（可为 null）
     * @param registryAccess 注册表访问器（用于 ItemStack.save）
     * @return 二进制数据
     */
    public static byte[] serializeItemHandlerToBytes(IItemHandler handler,
                                                     String containerType,
                                                     Component customName,
                                                     RegistryAccess registryAccess) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 1. 写入容器类型
            buf.writeUtf(containerType);

            // 2. 写入自定义名称
            if (customName != null) {
                buf.writeBoolean(true);
                buf.writeUtf(Component.Serializer.toJson(customName, registryAccess));
            } else {
                buf.writeBoolean(false);
            }

            // 3. 写入物品列表
            List<CompoundTag> items = new ArrayList<>();
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    items.add(serializeItemStack(stack, registryAccess));
                }
            }

            buf.writeVarInt(items.size());
            for (CompoundTag itemTag : items) {
                buf.writeNbt(itemTag);
            }

            // 4. 读取结果
            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return result;
        } finally {
            buf.release();
        }
    }

    /**
     * 从二进制数据反序列化容器数据
     *
     * @param data           二进制数据
     * @param registryAccess 注册表访问器（用于 Component 反序列化）
     * @return 容器 NBT 数据
     */
    public static ContainerNbtData deserializeContainerFromBytes(byte[] data, RegistryAccess registryAccess) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            // 1. 读取容器类型
            String containerType = buf.readUtf();

            // 2. 读取自定义名称
            Component customName = null;
            if (buf.readBoolean()) {
                String nameJson = buf.readUtf();
                customName = Component.Serializer.fromJson(nameJson, registryAccess);
            }

            // 3. 读取物品列表
            int itemCount = buf.readVarInt();
            List<CompoundTag> itemTags = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                CompoundTag tag = buf.readNbt();
                if (tag != null) {
                    itemTags.add(tag);
                }
            }

            return new ContainerNbtData(containerType, customName, itemTags);
        } finally {
            buf.release();
        }
    }

    // ======================== 物品序列化辅助 ========================

    /**
     * 将单个 ItemStack 序列化为 CompoundTag
     *
     * @param stack          物品栈
     * @param registryAccess 注册表访问器
     * @return CompoundTag 表示的物品数据
     */
    public static CompoundTag serializeItemStack(ItemStack stack, RegistryAccess registryAccess) {
        Tag tag = stack.save(registryAccess);
        if (tag instanceof CompoundTag compoundTag) {
            return compoundTag;
        }
        // 如果 save 返回的不是 CompoundTag（理论上不会），包装一层
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("item", tag);
        return wrapper;
    }

    /**
     * 从 CompoundTag 反序列化为 ItemStack
     *
     * @param tag            物品 NBT 数据
     * @param registryAccess 注册表访问器
     * @return ItemStack 实例（解析失败返回空物品）
     */
    public static ItemStack deserializeItemStack(CompoundTag tag, RegistryAccess registryAccess) {
        return ItemStack.parse(registryAccess, tag).orElse(ItemStack.EMPTY);
    }

    /**
     * 计算物品数据的明文哈希（用于凭证校验）
     * <p>
     * 对容器中所有物品的 NBT 数据计算 SHA-256 哈希，
     * 取前 8 字节作为校验值。
     * </p>
     *
     * @param container     容器对象
     * @param registryAccess 注册表访问器
     * @return 前 8 字节的 Hex 字符串（16 字符）
     */
    public static String computeItemHash(Container container, RegistryAccess registryAccess) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");

            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    CompoundTag itemTag = serializeItemStack(stack, registryAccess);
                    byte[] nbtBytes = compoundTagToBytes(itemTag);
                    md.update(nbtBytes);
                }
            }

            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i] & 0xFF));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 计算二进制数据的明文哈希（用于凭证校验）
     * <p>
     * 对序列化后的二进制数据计算 SHA-256 哈希，取前 8 字节。
     * </p>
     *
     * @param serializedData 已序列化的二进制数据
     * @return 前 8 字节的 Hex 字符串（16 字符）
     */
    public static String computeSerializedHash(byte[] serializedData) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(serializedData);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i] & 0xFF));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    // ======================== 内部辅助 ========================

    /**
     * 获取容器类型标识
     * <p>
     * 如果容器是 BlockEntity，返回方块的 ResourceLocation；
     * 否则返回 "sneakernet:inventory"。
     * </p>
     */
    private static String getContainerType(Container container) {
        if (container instanceof BlockEntity be) {
            return BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
        }
        return "sneakernet:inventory";
    }

    /**
     * 获取容器自定义名称
     * <p>
     * 如果容器实现了 Nameable 接口且有自定义名称，则返回；
     * 否则返回 null。
     * </p>
     */
    private static Component getContainerCustomName(Container container) {
        if (container instanceof Nameable nameable) {
            return nameable.getCustomName();
        }
        return null;
    }

    /**
     * CompoundTag → byte[]（使用 FriendlyByteBuf）
     */
    private static byte[] compoundTagToBytes(CompoundTag tag) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeNbt(tag);
            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return result;
        } finally {
            buf.release();
        }
    }
}
