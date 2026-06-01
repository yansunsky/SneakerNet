package com.yansunsky.sneakernet.data;

import com.google.gson.*;
import com.yansunsky.sneakernet.SneakerNet;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 离线凭证数据结构（v2 — JSON 格式）
 * <p>
 * 从旧版 Base64 二进制格式改为 JSON 格式，使用 Gson 序列化。
 * 支持非对称加密（ECC P-256）和对称加密（AES-GCM）混合模型。
 * </p>
 *
 * @param version             协议版本 = 2
 * @param issuerKeyId         签发者 KeyID（SHA256 公钥前8字节 Hex）
 * @param ephemeralPublicKey  临时公钥 Base64(DER X.509)，用于 ECDH 密钥协商
 * @param encryptedData       AES-GCM 密文 Base64
 * @param iv                  AES-GCM 12 字节 IV Base64
 * @param playerUuid          导出玩家 UUID
 * @param timestamp           Unix 秒时间戳
 * @param itemHash            SHA256(明文NBT) 前8字节 Hex（明文校验）
 * @param ttl                 有效期秒
 * @param signature           ECDSA 签名 Base64
 */
public record Voucher(
        int version,
        String issuerKeyId,
        String ephemeralPublicKey,
        String encryptedData,
        String iv,
        UUID playerUuid,
        long timestamp,
        String itemHash,
        int ttl,
        String signature
) {

    /** 协议版本号 */
    public static final int PROTOCOL_VERSION = 2;

    /** Gson 实例（带 UUID TypeAdapter + pretty printing） */
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new UuidTypeAdapter())
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * UUID TypeAdapter — 将 UUID 序列化为标准字符串（带连字符）
     */
    private static class UuidTypeAdapter extends TypeAdapter<UUID> {
        @Override
        public void write(JsonWriter out, UUID value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public UUID read(JsonReader in) throws IOException {
            String str = in.nextString();
            return UUID.fromString(str);
        }
    }

    /**
     * 创建 Voucher 实例（自动设置版本号）
     */
    public Voucher {
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "不支持的凭证版本: " + version + "，当前版本 = " + PROTOCOL_VERSION);
        }
    }

    /**
     * 构建器 — 方便创建 Voucher 实例
     */
    public static class Builder {
        private String issuerKeyId;
        private String ephemeralPublicKey;
        private String encryptedData;
        private String iv;
        private UUID playerUuid;
        private long timestamp;
        private String itemHash;
        private int ttl = 86400; // 默认 24 小时
        private String signature;

        public Builder issuerKeyId(String issuerKeyId) {
            this.issuerKeyId = issuerKeyId;
            return this;
        }

        public Builder ephemeralPublicKey(String ephemeralPublicKey) {
            this.ephemeralPublicKey = ephemeralPublicKey;
            return this;
        }

        public Builder encryptedData(String encryptedData) {
            this.encryptedData = encryptedData;
            return this;
        }

        public Builder iv(String iv) {
            this.iv = iv;
            return this;
        }

        public Builder playerUuid(UUID playerUuid) {
            this.playerUuid = playerUuid;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder itemHash(String itemHash) {
            this.itemHash = itemHash;
            return this;
        }

        public Builder ttl(int ttl) {
            this.ttl = ttl;
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        /**
         * 设置时间戳为当前 Unix 秒
         */
        public Builder now() {
            this.timestamp = System.currentTimeMillis() / 1000;
            return this;
        }

        public Voucher build() {
            return new Voucher(
                    PROTOCOL_VERSION,
                    issuerKeyId,
                    ephemeralPublicKey,
                    encryptedData,
                    iv,
                    playerUuid,
                    timestamp,
                    itemHash,
                    ttl,
                    signature
            );
        }
    }

    // ======================== JSON 序列化 ========================

    /**
     * 序列化为 JSON 字符串
     *
     * @return 格式化 JSON 字符串
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * 从 JSON 字符串反序列化
     *
     * @param json JSON 字符串
     * @return Voucher 实例
     * @throws JsonSyntaxException 如果 JSON 格式错误
     */
    public static Voucher fromJson(String json) {
        return GSON.fromJson(json, Voucher.class);
    }

    // ======================== 文件 I/O ========================

    /**
     * 生成文件名：export_{timestamp}_{random8hex}.json
     *
     * @return 文件名字符串
     */
    public String generateFileName() {
        String randomHex = HexFormat.of().formatHex(
                new java.security.SecureRandom().generateSeed(4)
        );
        return String.format("export_%d_%s.json", timestamp, randomHex);
    }

    /**
     * 保存到 JSON 文件
     *
     * @param dir 目录路径
     * @return 实际保存的文件路径
     * @throws IOException 如果文件写入失败
     */
    public Path saveToFile(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path filePath = dir.resolve(generateFileName());
        Files.writeString(filePath, toJson());
        SneakerNet.LOGGER.debug("[SneakerNet] 凭证已保存到 {}", filePath);
        return filePath;
    }

    /**
     * 从 JSON 文件读取凭证
     *
     * @param file JSON 文件路径
     * @return Voucher 实例
     * @throws IOException 如果文件读取失败
     */
    public static Voucher fromFile(Path file) throws IOException {
        String json = Files.readString(file);
        return fromJson(json);
    }

    // ======================== 业务校验 ========================

    /**
     * 检查凭证是否已过期
     *
     * @return true 表示已过期
     */
    public boolean isExpired() {
        long now = System.currentTimeMillis() / 1000;
        return (now - timestamp) > ttl;
    }

    /**
     * 计算剩余有效时间（秒）
     *
     * @return 剩余秒数，负数表示已过期
     */
    public long remainingTtl() {
        long now = System.currentTimeMillis() / 1000;
        return ttl - (now - timestamp);
    }

    /**
     * 生成唯一凭证 ID（用于黑名单去重）
     * <p>
     * 使用 issuerKeyId + encryptedData + iv 组合的 SHA-256 哈希
     * </p>
     */
    public String computeVoucherId() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(issuerKeyId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(encryptedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(iv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    /**
     * 验证签名前的待签名数据（不包含 signature 字段）
     * <p>
     * 将除 signature 外的所有字段按固定顺序拼接，用于签名/验签
     * </p>
     *
     * @return 待签名数据字节
     */
    public byte[] getSignableData() {
        // 按字段声明顺序拼接，排除 signature
        String data = version + ":" +
                issuerKeyId + ":" +
                ephemeralPublicKey + ":" +
                encryptedData + ":" +
                iv + ":" +
                playerUuid + ":" +
                timestamp + ":" +
                itemHash + ":" +
                ttl;
        return data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
