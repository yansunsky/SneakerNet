package com.yansunsky.sneakernet.data;

import com.yansunsky.sneakernet.SneakerNet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家目标服务器绑定数据
 * <p>
 * 使用 {@link SavedData} 持久化存储每个玩家绑定的目标服务器名称。
 * 数据保存在 {@code world/data/sneakernet_player_binds.dat} 中。
 * </p>
 *
 * <pre>
 * 数据结构 (NBT):
 * {
 *   "bindings": [
 *     { "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", "server": "server_name" },
 *     ...
 *   ]
 * }
 * </pre>
 */
public class PlayerBindData extends SavedData {

    private static final Logger LOGGER = SneakerNet.LOGGER;
    private static final String DATA_NAME = SneakerNet.MOD_ID + "_player_binds";

    /** 玩家 UUID → 目标服务器名 */
    private final Map<UUID, String> bindings = new HashMap<>();

    // ─── 工厂方法 ───

    /**
     * 获取/创建 PlayerBindData 实例
     *
     * @param server Minecraft 服务器实例
     * @return PlayerBindData 单例
     */
    public static PlayerBindData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(PlayerBindData::new, PlayerBindData::load),
                DATA_NAME
        );
    }

    /**
     * 从 NBT 加载数据
     */
    public static PlayerBindData load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        PlayerBindData data = new PlayerBindData();
        if (tag.contains("bindings", Tag.TAG_LIST)) {
            ListTag list = tag.getList("bindings", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                try {
                    UUID uuid = UUID.fromString(entry.getString("uuid"));
                    String server = entry.getString("server");
                    data.bindings.put(uuid, server);
                } catch (Exception e) {
                    LOGGER.warn("[SneakerNet] 跳过无效的玩家绑定条目: {}", entry, e);
                }
            }
        }
        return data;
    }

    /**
     * 保存到 NBT
     */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> entry : bindings.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("uuid", entry.getKey().toString());
            entryTag.putString("server", entry.getValue());
            list.add(entryTag);
        }
        tag.put("bindings", list);
        return tag;
    }

    // ─── 业务方法 ───

    /**
     * 绑定玩家到指定服务器
     *
     * @param playerUuid 玩家 UUID
     * @param serverName 目标服务器名
     */
    public void setBinding(UUID playerUuid, String serverName) {
        bindings.put(playerUuid, serverName);
        setDirty();
        LOGGER.debug("[SneakerNet] 玩家 {} 绑定到服务器 {}", playerUuid, serverName);
    }

    /**
     * 清除玩家的绑定
     *
     * @param playerUuid 玩家 UUID
     */
    public void removeBinding(UUID playerUuid) {
        bindings.remove(playerUuid);
        setDirty();
        LOGGER.debug("[SneakerNet] 玩家 {} 已清除绑定", playerUuid);
    }

    /**
     * 获取玩家绑定的目标服务器名
     *
     * @param playerUuid 玩家 UUID
     * @return 服务器名，null 表示未绑定
     */
    public String getBinding(UUID playerUuid) {
        return bindings.get(playerUuid);
    }

    /**
     * 检查玩家是否已绑定
     */
    public boolean hasBinding(UUID playerUuid) {
        return bindings.containsKey(playerUuid);
    }
}
