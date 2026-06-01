package com.yansunsky.sneakernet.data;

import com.yansunsky.sneakernet.SneakerNet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

/**
 * SQLite 黑名单管理器（v2 — 基于 config/sneakernet/blacklist.db）
 * <p>
 * 从旧版 world 目录改为 config/sneakernet/blacklist.db，
 * 表结构升级为支持 issuer_id 和 ttl_seconds 字段。
 * </p>
 *
 * <pre>
 * 表结构:
 *   blacklist(
 *     voucher_id   TEXT PRIMARY KEY,  -- 凭证唯一 ID (SHA-256 hash)
 *     issuer_id    TEXT,               -- 签发者 KeyID
 *     player_uuid  TEXT,               -- 玩家 UUID
 *     used_at      INTEGER,            -- 核销时间 (Unix 秒)
 *     ttl_seconds  INTEGER             -- 凭证有效期 (秒)
 *   )
 * </pre>
 */
public class VoucherBlacklist {

    /** 数据库文件路径 */
    private final Path dbPath;

    /** SQLite 连接 */
    private Connection connection;

    /**
     * 构造黑名单管理器
     *
     * @param configDir 配置目录（config/sneakernet/），数据库文件为 configDir/blacklist.db
     */
    public VoucherBlacklist(Path configDir) {
        this.dbPath = configDir.resolve("blacklist.db");
    }

    /**
     * 初始化数据库：创建表、索引，启用 WAL 模式
     *
     * @throws SQLException 如果数据库初始化失败
     */
    public synchronized void initialize() throws SQLException {
        // 确保 SQLite JDBC 驱动已加载
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到 SQLite JDBC 驱动", e);
        }

        // 确保目录存在
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("无法创建数据库目录: " + dbPath.getParent(), e);
        }

        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.connection = DriverManager.getConnection(jdbcUrl);

        try (Statement stmt = connection.createStatement()) {
            // 启用 WAL 模式（写前日志，提高并发性能）
            stmt.execute("PRAGMA journal_mode=WAL");

            // 启用外键约束
            stmt.execute("PRAGMA foreign_keys=ON");

            // 创建黑名单表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS blacklist (
                    voucher_id   TEXT PRIMARY KEY,
                    issuer_id    TEXT,
                    player_uuid  TEXT,
                    used_at      INTEGER NOT NULL,
                    ttl_seconds  INTEGER NOT NULL DEFAULT 86400
                )
                """);

            // 创建索引：按玩家查询
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_blacklist_player
                ON blacklist(player_uuid)
                """);

            // 创建索引：按签发者查询
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_blacklist_issuer
                ON blacklist(issuer_id)
                """);

            // 创建索引：按时间查询（用于清理过期记录）
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_blacklist_used_at
                ON blacklist(used_at)
                """);
        }

        SneakerNet.LOGGER.info("[SneakerNet] 黑名单数据库已初始化: {}", dbPath);
    }

    /**
     * 检查凭证是否已被核销
     *
     * @param voucherId 凭证唯一 ID
     * @return true = 已核销（拒绝），false = 未核销（放行）
     * @throws SQLException 如果查询失败
     */
    public synchronized boolean isRedeemed(String voucherId) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM blacklist WHERE voucher_id = ?")) {
            ps.setString(1, voucherId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 将凭证标记为已核销
     * <p>
     * 调用此方法前必须确保凭证通过了签名验证。
     * </p>
     *
     * @param voucherId 凭证唯一 ID
     * @param issuerId  签发者 KeyID
     * @param playerUuid 玩家 UUID
     * @param ttl       凭证有效期（秒）
     * @throws SQLException 如果写入失败或凭证已存在
     */
    public synchronized void redeem(String voucherId, String issuerId, UUID playerUuid, int ttl)
            throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO blacklist (voucher_id, issuer_id, player_uuid, used_at, ttl_seconds) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, voucherId);
            ps.setString(2, issuerId);
            ps.setString(3, playerUuid.toString());
            ps.setLong(4, System.currentTimeMillis() / 1000);
            ps.setInt(5, ttl);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                SneakerNet.LOGGER.info("[SneakerNet] 凭证已核销: {} (玩家: {}, 签发者: {})",
                        voucherId, playerUuid, issuerId);
            } else {
                SneakerNet.LOGGER.warn("[SneakerNet] 凭证核销跳过（可能已存在）: {}", voucherId);
            }
        }
    }

    /**
     * 清理过期的黑名单记录
     * <p>
     * 删除 used_at + ttl_seconds < 当前时间 的记录，
     * 即已经超过有效期的核销记录可以安全删除（因为对应的凭证也已过期，无法再次使用）。
     * </p>
     *
     * @throws SQLException 如果删除失败
     */
    public synchronized void cleanExpired() throws SQLException {
        ensureConnection();
        long now = System.currentTimeMillis() / 1000;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM blacklist WHERE (used_at + ttl_seconds) < ?")) {
            ps.setLong(1, now);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                SneakerNet.LOGGER.info("[SneakerNet] 清理了 {} 条过期黑名单记录", deleted);
            }
        }
    }

    /**
     * 关闭数据库连接
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    SneakerNet.LOGGER.info("[SneakerNet] 黑名单数据库连接已关闭");
                }
            } catch (SQLException e) {
                SneakerNet.LOGGER.error("[SneakerNet] 关闭黑名单数据库连接失败", e);
            }
        }
    }

    /**
     * 确保连接有效
     */
    private void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("黑名单数据库连接未初始化或已关闭");
        }
    }
}
