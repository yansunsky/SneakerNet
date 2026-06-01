package com.yansunsky.sneakernet.crypto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.yansunsky.sneakernet.SneakerNet;

import javax.crypto.SecretKey;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * ECC 密钥管理器
 * <p>
 * 管理 P-256 密钥对的生成、DER 二进制 I/O、Base64 公钥交换、
 * 以及可信服务器公钥的 JSON 文件导入/导出。
 * </p>
 *
 * <pre>
 * 目录结构:
 *   config/sneakernet/
 *   ├── local_key.der       # 私钥 (PKCS#8 DER)
 *   ├── local_pub.der       # 公钥 (X.509 DER)
 *   ├── local_pub.json      # 公钥 JSON (用于交换)
 *   ├── trusted_keys.json   # 可信服务器公钥列表
 *   └── blacklist.db         # SQLite 黑名单 (VoucherBlacklist 管理)
 * </pre>
 */
public class KeyManager {

    /** EC 曲线名称 */
    private static final String EC_ALGORITHM = "EC";
    /** P-256 曲线标准名 */
    private static final String CURVE_NAME = "secp256r1";
    /** 签名算法 */
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    /** 配置根目录（config/sneakernet/） */
    private final Path configDir;
    /** 本地私钥文件 */
    private final Path privateKeyFile;
    /** 本地公钥文件（DER） */
    private final Path publicKeyFile;
    /** 本地公钥 JSON 文件 */
    private final Path publicKeyJsonFile;
    /** 可信密钥列表文件 */
    private final Path trustedKeysFile;

    /** 本地密钥对 */
    private KeyPair keyPair;
    /** 可信服务器公钥映射：keyId → TrustedServer */
    private final Map<String, TrustedServer> trustedServers = new LinkedHashMap<>();

    /** Gson 实例（复用 Voucher 的 UUID 适配器） */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /**
     * 可信服务器记录
     *
     * @param name           服务器名称（人类可读）
     * @param keyId          密钥 ID（SHA256 公钥前8字节 Hex）
     * @param pubKeyBase64   公钥 DER Base64 编码
     * @param fingerprint    公钥指纹（冒号分隔 Hex）
     */
    public record TrustedServer(
            String name,
            String keyId,
            String pubKeyBase64,
            String fingerprint
    ) {}

    /**
     * 构造密钥管理器
     *
     * @param configDir 配置根目录（config/sneakernet/）
     */
    public KeyManager(Path configDir) {
        this.configDir = configDir;
        this.privateKeyFile = configDir.resolve("local_key.der");
        this.publicKeyFile = configDir.resolve("local_pub.der");
        this.publicKeyJsonFile = configDir.resolve("local_pub.json");
        this.trustedKeysFile = configDir.resolve("trusted_keys.json");
    }

    // ======================== 密钥对管理 ========================

    /**
     * 加载或生成 ECC P-256 密钥对
     * <p>
     * 若本地已有 DER 密钥文件则加载，否则生成新密钥对并保存。
     * 同时加载可信服务器公钥列表。
     * </p>
     *
     * @throws GeneralSecurityException 如果密钥生成或加载失败
     * @throws IOException              如果文件读写失败
     */
    public void loadOrGenerate() throws GeneralSecurityException, IOException {
        Files.createDirectories(configDir);

        if (Files.exists(privateKeyFile) && Files.exists(publicKeyFile)) {
            // 从 DER 文件加载密钥对
            loadKeyPair();
            SneakerNet.LOGGER.info("[SneakerNet] 已加载本地密钥对，KeyID = {}", computeKeyId(keyPair.getPublic()));
        } else {
            // 生成新的 P-256 密钥对
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(EC_ALGORITHM);
            kpg.initialize(256); // P-256 = secp256r1
            keyPair = kpg.generateKeyPair();

            // 保存为 DER 二进制
            saveKeyPair();
            SneakerNet.LOGGER.info("[SneakerNet] 已生成新密钥对，KeyID = {}", computeKeyId(keyPair.getPublic()));
        }

        // 加载可信服务器列表
        loadTrustedServers();
    }

    /**
     * 获取本地公钥
     */
    public PublicKey getPublicKey() {
        return keyPair != null ? keyPair.getPublic() : null;
    }

    /**
     * 获取本地私钥
     */
    public PrivateKey getPrivateKey() {
        return keyPair != null ? keyPair.getPrivate() : null;
    }

    /**
     * 获取本地密钥对
     */
    public KeyPair getKeyPair() {
        return keyPair;
    }

    /**
     * 获取本地密钥对（别名方法，与 getKeyPair() 相同）
     * <p>
     * 被 TicketItem 和 SneakerNetCommands 调用。
     * </p>
     */
    public KeyPair getLocalKeyPair() {
        return keyPair;
    }

    /**
     * 获取本地公钥指纹
     * <p>
     * 被 SneakerNetCommands 调用，用于显示服务器公钥信息。
     * </p>
     *
     * @return 本地公钥指纹字符串，密钥对未加载时返回 "N/A"
     */
    public String getLocalFingerprint() {
        return keyPair != null ? computeFingerprint(keyPair.getPublic()) : "N/A";
    }

    /**
     * 获取配置目录路径
     * <p>
     * 被 SneakerNetCommands 调用，用于定位公钥导出文件等。
     * </p>
     *
     * @return 配置根目录路径（config/sneakernet/）
     */
    public Path getConfigDir() {
        return configDir;
    }

    // ======================== 公钥交换 ========================

    /**
     * 导出公钥到 JSON 文件（用于服务器间交换）
     * <p>
     * 生成文件格式：
     * </p>
     * <pre>
     * {
     *   "serverName": "survival-server",
     *   "keyId": "a1b2c3d4e5f6a7b8",
     *   "publicKeyBase64": "Base64(DER X.509)",
     *   "fingerprint": "a1:b2:c3:..."
     * }
     * </pre>
     *
     * @param serverName 当前服务器名称（写入 JSON 供对方识别）
     * @return 生成的 JSON 文件路径
     * @throws IOException 如果文件写入失败
     */
    public Path exportPublicKeyJson(String serverName) throws IOException {
        if (keyPair == null) {
            throw new IllegalStateException("密钥对尚未加载，请先调用 loadOrGenerate()");
        }

        PublicKey pubKey = keyPair.getPublic();
        String pubKeyBase64 = Base64.getEncoder().encodeToString(pubKey.getEncoded());

        Map<String, String> json = new LinkedHashMap<>();
        json.put("serverName", serverName);
        json.put("keyId", computeKeyId(pubKey));
        json.put("publicKeyBase64", pubKeyBase64);
        json.put("fingerprint", computeFingerprint(pubKey));

        String jsonStr = GSON.toJson(json);
        Files.writeString(publicKeyJsonFile, jsonStr);

        SneakerNet.LOGGER.info("[SneakerNet] 公钥已导出到 {}", publicKeyJsonFile);
        return publicKeyJsonFile;
    }

    /**
     * 从 JSON 文件导入可信服务器公钥
     *
     * @param name        服务器名称（本地方便识别的别名）
     * @param pubJsonFile 对方导出的公钥 JSON 文件路径
     * @throws IOException              如果文件读取失败
     * @throws GeneralSecurityException 如果公钥解析失败
     */
    public void addTrustedServer(String name, Path pubJsonFile) throws IOException, GeneralSecurityException {
        String jsonStr = Files.readString(pubJsonFile);

        // 读取 JSON 为 Map
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();
        Map<String, String> data = GSON.fromJson(jsonStr, mapType);

        String keyId = data.get("keyId");
        String pubKeyBase64 = data.get("publicKeyBase64");
        String fingerprint = data.get("fingerprint");

        if (keyId == null || pubKeyBase64 == null) {
            throw new IllegalArgumentException("公钥 JSON 文件缺少 keyId 或 publicKeyBase64 字段");
        }

        // 解析公钥
        byte[] pubKeyBytes = Base64.getDecoder().decode(pubKeyBase64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes);
        KeyFactory kf = KeyFactory.getInstance(EC_ALGORITHM);
        PublicKey pubKey = kf.generatePublic(keySpec);

        // 验证指纹一致性
        String computedFingerprint = computeFingerprint(pubKey);
        if (fingerprint != null && !computedFingerprint.equalsIgnoreCase(fingerprint)) {
            throw new GeneralSecurityException(
                    "公钥指纹不匹配！JSON 中记录 = " + fingerprint + "，实际计算 = " + computedFingerprint);
        }

        // 验证 KeyID 一致性
        String computedKeyId = computeKeyId(pubKey);
        if (!computedKeyId.equalsIgnoreCase(keyId)) {
            throw new GeneralSecurityException(
                    "KeyID 不匹配！JSON 中记录 = " + keyId + "，实际计算 = " + computedKeyId);
        }

        // 添加到可信列表
        TrustedServer server = new TrustedServer(name, keyId, pubKeyBase64, computedFingerprint);
        trustedServers.put(keyId, server);

        // 持久化
        saveTrustedServers();

        SneakerNet.LOGGER.info("[SneakerNet] 已添加可信服务器: name={}, keyId={}", name, keyId);
    }

    /**
     * 移除可信服务器公钥
     *
     * @param name 服务器名称
     * @return true 表示成功移除，false 表示未找到
     * @throws IOException 如果保存失败
     */
    public boolean removeTrustedServer(String name) throws IOException {
        boolean removed = trustedServers.entrySet().removeIf(e -> e.getValue().name().equals(name));
        if (removed) {
            saveTrustedServers();
            SneakerNet.LOGGER.info("[SneakerNet] 已移除可信服务器: name={}", name);
        }
        return removed;
    }

    /**
     * 按 KeyID 查找可信公钥
     *
     * @param keyId 密钥 ID
     * @return 公钥对象，未找到返回 null
     */
    public PublicKey getTrustedPublicKey(String keyId) {
        TrustedServer server = trustedServers.get(keyId);
        if (server == null) {
            return null;
        }
        try {
            byte[] pubKeyBytes = Base64.getDecoder().decode(server.pubKeyBase64());
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes);
            KeyFactory kf = KeyFactory.getInstance(EC_ALGORITHM);
            return kf.generatePublic(keySpec);
        } catch (GeneralSecurityException e) {
            SneakerNet.LOGGER.error("[SneakerNet] 解析可信公钥失败: keyId={}", keyId, e);
            return null;
        }
    }

    /**
     * 获取所有可信服务器
     */
    public Collection<TrustedServer> getTrustedServers() {
        return Collections.unmodifiableCollection(trustedServers.values());
    }

    // ======================== KeyID 与指纹 ========================

    /**
     * 计算公钥的 KeyID
     * <p>
     * KeyID = SHA256(DER 编码公钥) 前 8 字节的 Hex 字符串
     * </p>
     *
     * @param pubKey 公钥
     * @return KeyID（16 字符 Hex 字符串）
     */
    public static String computeKeyId(PublicKey pubKey) {
        byte[] hash = sha256(pubKey.getEncoded());
        return bytesToHex(hash, 0, 8);
    }

    /**
     * 计算公钥指纹
     * <p>
     * 指纹 = SHA256(DER 编码公钥) 的冒号分隔 Hex 字符串
     * </p>
     *
     * @param pubKey 公钥
     * @return 指纹字符串（如 "a1:b2:c3:...:ff"）
     */
    public static String computeFingerprint(PublicKey pubKey) {
        byte[] hash = sha256(pubKey.getEncoded());
        return bytesToFingerprint(hash);
    }

    // ======================== 公钥编解码 ========================

    /**
     * 将 Base64 编码的 DER X.509 公钥字符串解码为 PublicKey 对象
     * <p>
     * 被 TicketItem 和 VoucherCrypto 调用。
     * </p>
     *
     * @param base64 Base64 编码的 DER X.509 公钥字符串
     * @return 解码后的 PublicKey 对象
     * @throws GeneralSecurityException 如果解码失败
     */
    public static PublicKey decodePublicKeyFromBase64(String base64) throws GeneralSecurityException {
        byte[] pubKeyBytes = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes);
        KeyFactory kf = KeyFactory.getInstance(EC_ALGORITHM);
        return kf.generatePublic(keySpec);
    }

    // ======================== 签名/验证 ========================

    /**
     * 使用本地私钥对数据签名
     *
     * @param data 待签名数据
     * @return DER 编码的 ECDSA 签名
     * @throws GeneralSecurityException 如果签名失败
     */
    public byte[] sign(byte[] data) throws GeneralSecurityException {
        if (keyPair == null) {
            throw new IllegalStateException("密钥对尚未加载");
        }
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initSign(keyPair.getPrivate());
        sig.update(data);
        return sig.sign();
    }

    /**
     * 使用指定公钥验证签名
     *
     * @param pubKey   公钥
     * @param data     原始数据
     * @param signature 签名
     * @return true 如果签名有效
     * @throws GeneralSecurityException 如果验证过程出错
     */
    public boolean verify(PublicKey pubKey, byte[] data, byte[] signature) throws GeneralSecurityException {
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initVerify(pubKey);
        sig.update(data);
        return sig.verify(signature);
    }

    // ======================== 内部方法 ========================

    /**
     * 从 DER 文件加载密钥对
     */
    private void loadKeyPair() throws GeneralSecurityException, IOException {
        byte[] privKeyBytes = Files.readAllBytes(privateKeyFile);
        byte[] pubKeyBytes = Files.readAllBytes(publicKeyFile);

        KeyFactory kf = KeyFactory.getInstance(EC_ALGORITHM);

        PKCS8EncodedKeySpec privSpec = new PKCS8EncodedKeySpec(privKeyBytes);
        PrivateKey privKey = kf.generatePrivate(privSpec);

        X509EncodedKeySpec pubSpec = new X509EncodedKeySpec(pubKeyBytes);
        PublicKey pubKey = kf.generatePublic(pubSpec);

        keyPair = new KeyPair(pubKey, privKey);
    }

    /**
     * 将密钥对保存为 DER 文件
     */
    private void saveKeyPair() throws IOException {
        // 私钥 → PKCS#8 DER
        Files.write(privateKeyFile, keyPair.getPrivate().getEncoded());
        // 公钥 → X.509 DER
        Files.write(publicKeyFile, keyPair.getPublic().getEncoded());

        SneakerNet.LOGGER.debug("[SneakerNet] 密钥对已保存到 {}", configDir);
    }

    /**
     * 加载可信服务器列表
     */
    private void loadTrustedServers() throws IOException {
        trustedServers.clear();
        if (!Files.exists(trustedKeysFile)) {
            return;
        }

        String json = Files.readString(trustedKeysFile);
        Type listType = new TypeToken<List<TrustedServer>>() {}.getType();
        List<TrustedServer> servers = GSON.fromJson(json, listType);

        if (servers != null) {
            for (TrustedServer server : servers) {
                trustedServers.put(server.keyId(), server);
            }
        }

        SneakerNet.LOGGER.info("[SneakerNet] 已加载 {} 个可信服务器公钥", trustedServers.size());
    }

    /**
     * 保存可信服务器列表到 JSON
     */
    private void saveTrustedServers() throws IOException {
        List<TrustedServer> list = new ArrayList<>(trustedServers.values());
        String json = GSON.toJson(list);
        Files.writeString(trustedKeysFile, json);
    }

    /**
     * SHA-256 哈希
     */
    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 字节数组 → Hex 字符串（指定长度）
     */
    private static String bytesToHex(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length && i < bytes.length; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 字节数组 → 冒号分隔 Hex 指纹
     */
    private static String bytesToFingerprint(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3 - 1);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
