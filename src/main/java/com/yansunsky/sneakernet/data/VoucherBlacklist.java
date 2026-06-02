package com.yansunsky.sneakernet.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.yansunsky.sneakernet.SneakerNet;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 黑名单管理器（v3 — JSON 文件存储）
 * <p>
 * 从 SQLite 改为纯 JSON 文件，避免 NeoForge 类加载器与 JDBC SPI 不兼容的问题。
 * 数据存储在 config/sneakernet/blacklist.json 中，格式为：
 * <pre>
 * {
 *   "entries": {
 *     "&lt;voucherId&gt;": { "redeemedAt": &lt;unix秒&gt;, "ttl": &lt;秒&gt;, "issuerId": "...", "playerUuid": "..." },
 *     ...
 *   }
 * }
 * </pre>
 */
public class VoucherBlacklist {

    /** 黑名单文件路径 */
    private final Path filePath;

    /** 内存中的黑名单数据 */
    private Map<String, BlacklistEntry> entries = new HashMap<>();

    /** Gson 实例 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** JSON 序列化类型 */
    private static final Type MAP_TYPE = new TypeToken<Map<String, BlacklistEntry>>() {}.getType();

    public VoucherBlacklist(Path configDir) {
        this.filePath = configDir.resolve("blacklist.json");
    }

    /**
     * 黑名单条目
     */
    private record BlacklistEntry(
            long redeemedAt,
            int ttl,
            String issuerId,
            String playerUuid
    ) {
        boolean isExpired() {
            return (System.currentTimeMillis() / 1000) - redeemedAt > ttl;
        }
    }

    /**
     * 初始化：确保目录存在，加载已有数据
     */
    public synchronized void initialize() throws IOException {
        // 确保目录存在
        Files.createDirectories(filePath.getParent());
        SneakerNet.LOGGER.info("[SneakerNet] 黑名单文件: {}", filePath);

        // 加载已有数据
        if (Files.exists(filePath)) {
            String json = Files.readString(filePath);
            Map<String, BlacklistEntry> loaded = GSON.fromJson(json, MAP_TYPE);
            if (loaded != null) {
                entries = loaded;
                SneakerNet.LOGGER.info("[SneakerNet] 黑名单已加载: {} 条记录", entries.size());
            }
        } else {
            SneakerNet.LOGGER.info("[SneakerNet] 黑名单文件不存在，将自动创建");
            save();
        }
    }

    /**
     * 保存黑名单到文件
     */
    private void save() throws IOException {
        Files.writeString(filePath, GSON.toJson(Map.of("entries", entries)));
    }

    /**
     * 检查凭证是否已被核销
     *
     * @param voucherId 凭证唯一 ID
     * @return true = 已核销（拒绝），false = 未核销（放行）
     */
    public synchronized boolean isRedeemed(String voucherId) {
        BlacklistEntry entry = entries.get(voucherId);
        if (entry == null) return false;
        if (entry.isExpired()) {
            // 过期记录自动清理
            entries.remove(voucherId);
            try { save(); } catch (IOException e) {
                SneakerNet.LOGGER.warn("[SneakerNet] 保存黑名单失败", e);
            }
            return false;
        }
        return true;
    }

    /**
     * 将凭证标记为已核销
     *
     * @param voucherId  凭证唯一 ID
     * @param issuerId   签发者 KeyID
     * @param playerUuid 玩家 UUID
     * @param ttl        凭证有效期（秒）
     * @throws IOException 如果写入失败
     */
    public synchronized void redeem(String voucherId, String issuerId, UUID playerUuid, int ttl)
            throws IOException {
        if (entries.containsKey(voucherId)) {
            SneakerNet.LOGGER.warn("[SneakerNet] 凭证核销跳过（可能已存在）: {}", voucherId);
            return;
        }

        entries.put(voucherId, new BlacklistEntry(
                System.currentTimeMillis() / 1000,
                ttl,
                issuerId,
                playerUuid.toString()
        ));
        save();

        SneakerNet.LOGGER.info("[SneakerNet] 凭证已核销: {} (玩家: {}, 签发者: {})",
                voucherId, playerUuid, issuerId);
    }

    /**
     * 清理过期的黑名单记录
     */
    public synchronized void cleanExpired() {
        int before = entries.size();
        entries.values().removeIf(BlacklistEntry::isExpired);
        int removed = before - entries.size();
        if (removed > 0) {
            try {
                save();
                SneakerNet.LOGGER.info("[SneakerNet] 清理了 {} 条过期黑名单记录", removed);
            } catch (IOException e) {
                SneakerNet.LOGGER.warn("[SneakerNet] 保存黑名单失败", e);
            }
        }
    }

    /**
     * 关闭（JSON 文件模式无需关闭连接，仅记录日志）
     */
    public synchronized void close() {
        SneakerNet.LOGGER.info("[SneakerNet] 黑名单已关闭，当前 {} 条记录", entries.size());
    }
}
