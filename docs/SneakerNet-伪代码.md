# SneakerNet — 项目伪代码

> **基于**：v0.4 设计计划书  
> **日期**：2026-06-02  
> **语言**：Java 风格伪代码（贴近 NeoForge 1.21.1 API）  

---

## 1. SneakerNet.java — Mod 主类

```
class SneakerNet {
    // 常量
    const MOD_ID = "sneakernet"
    const LOGGER = LoggerFactory.getLogger("SneakerNet")
    
    // 模块实例（延迟初始化）
    static KeyManager keyManager
    static VoucherBlacklist blacklist
    static CryptoExecutor cryptoExecutor
    
    // ─── Mod 生命周期事件 ───
    
    @SubscribeEvent
    onModConstruct(FMLConstructModEvent event) {
        // 1. 初始化配置系统
        Config.register()
        
        // 2. 注册物品
        ModItems.register()
        
        // 3. 注册命令
        SneakerNetCommands.register()
    }
    
    @SubscribeEvent
    onCommonSetup(FMLCommonSetupEvent event) {
        // 4. 初始化密钥管理器
        keyManager = new KeyManager(configDir)
        keyManager.loadOrGenerate()
        
        // 5. 初始化黑名单
        blacklist = new VoucherBlacklist(configDir)
        blacklist.initialize()
        
        // 6. 初始化线程池
        cryptoExecutor = new CryptoExecutor()
        
        // 7. 确保客户端目录存在
        ensureClientDirectories()
    }
    
    @SubscribeEvent
    onServerStopping(ServerStoppingEvent event) {
        // 8. 关闭线程池
        cryptoExecutor.shutdown()
        
        // 9. 关闭黑名单数据库连接
        blacklist.close()
    }
    
    // ─── 辅助方法 ───
    
    ensureClientDirectories() {
        // 客户端目录 = 游戏根目录/sneakernet/
        clientDir = gameDir.resolve("sneakernet")
        vouchersDir = clientDir.resolve("vouchers")
        redeemedDir = clientDir.resolve("redeemed")
        
        if (!vouchersDir.exists()) vouchersDir.createDirectories()
        if (!redeemedDir.exists()) redeemedDir.createDirectories()
    }
}
```

---

## 2. Config.java — 配置管理

```
class Config {
    // ─── 配置项 ───
    
    static ForgeConfigSpec SPEC
    
    // 服务端配置
    static ConfigValue<String> KEY_DIR          // 密钥文件目录，默认 "config/sneakernet/"
    static ConfigValue<Integer> VOUCHER_TTL_HOURS // 凭据有效期（小时），默认 24
    static ConfigValue<Integer> MAX_ITEMS_PER_VOUCHER // 单个凭据最大物品数，默认 54（双箱）
    static ConfigValue<Boolean> REQUIRE_PLAYER_MATCH // 是否要求玩家UUID匹配，默认 true
    
    // ─── 注册 ───
    
    static register() {
        BUILDER = new ForgeConfigSpec.Builder()
        
        BUILDER.push("keys")
        KEY_DIR = BUILDER
            .comment("密钥文件存储目录")
            .define("key_dir", "config/sneakernet/")
        BUILDER.pop()
        
        BUILDER.push("voucher")
        VOUCHER_TTL_HOURS = BUILDER
            .comment("凭据有效期（小时）")
            .defineInRange("ttl_hours", 24, 1, 720)
        MAX_ITEMS_PER_VOUCHER = BUILDER
            .comment("单个凭据最大物品数")
            .defineInRange("max_items", 54, 1, 54)
        REQUIRE_PLAYER_MATCH = BUILDER
            .comment("是否要求导入玩家与导出玩家UUID一致")
            .define("require_player_match", true)
        BUILDER.pop()
        
        SPEC = BUILDER.build()
    }
}
```

---

## 3. KeyManager.java — 密钥管理

```
class KeyManager {
    
    Path configDir  // config/sneakernet/
    
    // ─── 密钥对 ───
    KeyPair localKeyPair          // 本服密钥对
    String localKeyId             // 本服 KeyID = SHA256(pubKey) 的前8字节Hex
    String localFingerprint       // 本服指纹 = SHA256(pubKey) 的完整Hex（冒号分隔）
    
    // ─── 可信公钥列表 ───
    Map<String, TrustedServer> trustedServers  // name → TrustedServer
    
    // ─── 数据类 ───
    
    record TrustedServer(
        String name,           // 服务器名（管理员指定）
        String keyId,          // SHA256(pubKey) 前8字节Hex
        String pubKeyBase64,   // Base64(DER X.509)
        String fingerprint     // SHA256(pubKey) 冒号分隔Hex
    )
    
    // ─── 构造与加载 ───
    
    constructor(Path configDir) {
        this.configDir = configDir
        this.trustedServers = new LinkedHashMap<>()
    }
    
    loadOrGenerate() {
        privateKeyFile = configDir.resolve("local_key.der")
        publicKeyFile = configDir.resolve("local_pub.der")
        
        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            // 加载已有密钥
            localKeyPair = loadKeyPair(privateKeyFile, publicKeyFile)
            LOGGER.info("已加载本服密钥对，KeyID: {}", localKeyId)
        } else {
            // 生成新密钥对
            localKeyPair = generateKeyPair()
            saveKeyPair(localKeyPair, privateKeyFile, publicKeyFile)
            LOGGER.info("已生成新密钥对，KeyID: {}", localKeyId)
        }
        
        // 计算本服 KeyID 和指纹
        localKeyId = computeKeyId(localKeyPair.getPublic())
        localFingerprint = computeFingerprint(localKeyPair.getPublic())
        
        // 加载可信公钥列表
        loadTrustedKeys()
    }
    
    // ─── 密钥对生成 ───
    
    KeyPair generateKeyPair() {
        gen = KeyPairGenerator.getInstance("EC")
        spec = new ECGenParameterSpec("secp256r1")  // P-256
        gen.initialize(spec, SecureRandom.getInstanceStrong())
        return gen.generateKeyPair()
    }
    
    // ─── 密钥文件 I/O（DER 格式）───
    
    saveKeyPair(KeyPair kp, Path privFile, Path pubFile) {
        // 私钥：PKCS#8 DER
        privBytes = kp.getPrivate().getEncoded()  // PKCS#8 格式
        Files.write(privFile, privBytes)
        
        // 公钥：X.509 DER
        pubBytes = kp.getPublic().getEncoded()    // X.509 格式
        Files.write(pubFile, pubBytes)
    }
    
    KeyPair loadKeyPair(Path privFile, Path pubFile) {
        // 加载私钥
        privBytes = Files.readAllBytes(privFile)
        privSpec = new PKCS8EncodedKeySpec(privBytes)
        privateKey = KeyFactory.getInstance("EC").generatePrivate(privSpec)
        
        // 加载公钥
        pubBytes = Files.readAllBytes(pubFile)
        pubSpec = new X509EncodedKeySpec(pubBytes)
        publicKey = KeyFactory.getInstance("EC").generatePublic(pubSpec)
        
        return new KeyPair(publicKey, privateKey)
    }
    
    // ─── 公钥导出（JSON 格式）───
    
    Path exportPublicKeyJson(String serverName) {
        pubJson = new JsonObject()
        pubJson.addProperty("serverName", serverName)
        pubJson.addProperty("keyId", localKeyId)
        pubJson.addProperty("pubKeyBase64", 
            Base64.getEncoder().encodeToString(localKeyPair.getPublic().getEncoded()))
        pubJson.addProperty("generatedAt", Instant.now().toString())
        pubJson.addProperty("fingerprint", localFingerprint)
        
        filePath = configDir.resolve("local_pub.json")
        Files.writeString(filePath, new GsonBuilder().setPrettyPrinting().create().toJson(pubJson))
        return filePath
    }
    
    // ─── 可信公钥管理 ───
    
    loadTrustedKeys() {
        trustedFile = configDir.resolve("trusted_keys.json")
        if (!trustedFile.exists()) return
        
        json = Files.readString(trustedFile)
        doc = JsonParser.parseString(json).getAsJsonObject()
        serversArray = doc.getAsJsonArray("servers")
        
        for (entry : serversArray) {
            obj = entry.getAsJsonObject()
            server = new TrustedServer(
                name = obj.get("name").getAsString(),
                keyId = obj.get("keyId").getAsString(),
                pubKeyBase64 = obj.get("pubKeyBase64").getAsString(),
                fingerprint = obj.get("fingerprint").getAsString()
            )
            trustedServers.put(server.name, server)
        }
    }
    
    saveTrustedKeys() {
        doc = new JsonObject()
        serversArray = new JsonArray()
        
        for (server : trustedServers.values()) {
            obj = new JsonObject()
            obj.addProperty("name", server.name)
            obj.addProperty("keyId", server.keyId)
            obj.addProperty("pubKeyBase64", server.pubKeyBase64)
            obj.addProperty("fingerprint", server.fingerprint)
            serversArray.add(obj)
        }
        
        doc.add("servers", serversArray)
        Files.writeString(configDir.resolve("trusted_keys.json"), 
            new GsonBuilder().setPrettyPrinting().create().toJson(doc))
    }
    
    addTrustedServer(String name, Path pubJsonFile) {
        // 从 JSON 文件读取公钥
        json = Files.readString(pubJsonFile)
        doc = JsonParser.parseString(json).getAsJsonObject()
        
        pubKeyBase64 = doc.get("pubKeyBase64").getAsString()
        keyId = doc.get("keyId").getAsString()
        fingerprint = doc.get("fingerprint").getAsString()
        
        // 验证公钥格式有效
        pubKey = decodePublicKeyFromBase64(pubKeyBase64)
        computedKeyId = computeKeyId(pubKey)
        assert computedKeyId == keyId : "KeyID 不匹配，文件可能被篡改"
        
        server = new TrustedServer(name, keyId, pubKeyBase64, fingerprint)
        trustedServers.put(name, server)
        saveTrustedKeys()
    }
    
    removeTrustedServer(String name) {
        if (trustedServers.remove(name) != null) {
            saveTrustedKeys()
            return true  // 成功移除
        }
        return false  // 不存在
    }
    
    // ─── 工具方法 ───
    
    PublicKey decodePublicKeyFromBase64(String base64) {
        pubBytes = Base64.getDecoder().decode(base64)
        pubSpec = new X509EncodedKeySpec(pubBytes)
        return KeyFactory.getInstance("EC").generatePublic(pubSpec)
    }
    
    String computeKeyId(PublicKey pubKey) {
        hash = SHA256(pubKey.getEncoded())
        return hexEncode(hash).substring(0, 16)  // 前8字节 → 16个Hex字符
    }
    
    String computeFingerprint(PublicKey pubKey) {
        hash = SHA256(pubKey.getEncoded())
        return hexEncode(hash).replaceAll("(..)", "$1:").trimEnd(":")  // 冒号分隔
    }
    
    PublicKey getTrustedPublicKey(String keyId) {
        for (server : trustedServers.values()) {
            if (server.keyId == keyId) {
                return decodePublicKeyFromBase64(server.pubKeyBase64)
            }
        }
        return null  // 未找到
    }
}
```

---

## 4. HkdfUtil.java — HKDF 密钥派生

```
class HkdfUtil {
    
    // ─── HKDF-Extract（RFC 5869 §2.1）───
    
    static byte[] extract(byte[] salt, byte[] inputKeyMaterial) {
        // salt 为空时使用 HmacSHA256 输出长度的零字节
        if (salt == null || salt.length == 0) {
            salt = new byte[32]  // HmacSHA256 输出 = 32 bytes
        }
        
        mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(inputKeyMaterial)
    }
    
    // ─── HKDF-Expand（RFC 5869 §2.2）───
    
    static byte[] expand(byte[] prk, byte[] info, int length) {
        // prk = 伪随机密钥（来自 extract）
        // info = 上下文信息（协议标识等）
        // length = 输出密钥长度
        
        hashLen = 32  // HmacSHA256 输出长度
        n = ceil(length / hashLen)  // 迭代次数
        assert n <= 255 : "HKDF-Expand 输出长度不能超过 255 * hashLen"
        
        result = new byte[length]
        t = new byte[0]  // T(0) = 空字符串
        offset = 0
        
        for (i = 1; i <= n; i++) {
            mac = Mac.getInstance("HmacSHA256")
            mac.init(new SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)           // T(i-1)
            mac.update(info)        // info
            mac.update((byte) i)    // 计数器
            t = mac.doFinal()       // T(i)
            
            copyLen = min(hashLen, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
        }
        
        return result
    }
    
    // ─── 便捷方法：Extract + Expand ───
    
    static SecretKey deriveKey(byte[] salt, byte[] inputKeyMaterial, byte[] info, int length) {
        prk = extract(salt, inputKeyMaterial)
        okm = expand(prk, info, length)
        return new SecretKeySpec(okm, 0, length, "AES")
    }
}
```

---

## 5. VoucherCrypto.java — 加密核心

```
class VoucherCrypto {
    
    // ─── 常量 ───
    const GCM_IV_LENGTH = 12      // AES-GCM IV 长度
    const GCM_TAG_LENGTH = 128    // AES-GCM 认证标签位数
    const HKDF_INFO = "SneakerNet_v2_Encryption".getBytes(UTF_8)
    
    // ─── 导出加密（服务器 A 调用）───
    
    static Voucher encryptAndSign(
        KeyPair localKeyPair,       // 服务器 A 的密钥对
        PublicKey targetPubKey,     // 服务器 B 的公钥
        byte[] plaintextNbt,        // 容器 NBT 二进制数据
        UUID playerUuid,            // 导出玩家 UUID
        int ttlSeconds             // 凭据有效期（秒）
    ) {
        // [1] 生成临时 ECC 密钥对（前向安全）
        ephemeralKeyPair = generateEphemeralKeyPair()
        
        // [2] ECDH 密钥协商
        sharedSecret = ecdhKeyAgreement(
            ephemeralKeyPair.getPrivate(),  // A 的临时私钥
            targetPubKey                    // B 的公钥
        )
        
        // [3] HKDF 密钥派生
        salt = ephemeralKeyPair.getPublic().getEncoded()  // 临时公钥作为 salt
        aesKey = HkdfUtil.deriveKey(salt, sharedSecret, HKDF_INFO, 32)
        
        // [4] AES-256-GCM 加密
        iv = new byte[GCM_IV_LENGTH]
        SecureRandom.getInstanceStrong().nextBytes(iv)
        encryptedData = aesGcmEncrypt(aesKey, iv, plaintextNbt)
        
        // [5] 计算物品哈希（防内容替换）
        itemHash = SHA256(plaintextNbt)
        itemHashTruncated = Arrays.copyOfRange(itemHash, 0, 8)  // 前8字节
        
        // [6] 组装签名数据
        timestamp = Instant.now().getEpochSecond()
        signData = concat(
            ephemeralKeyPair.getPublic().getEncoded(),
            encryptedData,
            iv,
            playerUuid.toString().getBytes(UTF_8),
            longToBytes(timestamp),
            itemHashTruncated
        )
        
        // [7] ECDSA 签名（用 A 的长期私钥）
        signature = ecdsaSign(localKeyPair.getPrivate(), SHA256(signData))
        
        // [8] 组装 Voucher
        issuerKeyId = computeKeyId(localKeyPair.getPublic())
        
        return new Voucher(
            version = 2,
            issuerKeyId = issuerKeyId,
            ephemeralPublicKey = Base64.getEncoder().encodeToString(
                ephemeralKeyPair.getPublic().getEncoded()),
            encryptedData = Base64.getEncoder().encodeToString(encryptedData),
            iv = Base64.getEncoder().encodeToString(iv),
            playerUuid = playerUuid,
            timestamp = timestamp,
            itemHash = hexEncode(itemHashTruncated),
            ttl = ttlSeconds,
            signature = Base64.getEncoder().encodeToString(signature)
        )
    }
    
    // ─── 导入解密（服务器 B 调用）───
    
    static DecryptResult decryptAndVerify(
        KeyPair localKeyPair,       // 服务器 B 的密钥对
        Voucher voucher,            // 待验证的凭据
        KeyManager keyManager,      // 密钥管理器（查找签发者公钥）
        UUID currentPlayerUuid,     // 当前导入的玩家 UUID
        VoucherBlacklist blacklist  // 黑名单
    ) {
        // [1] 查找签发者公钥
        issuerPubKey = keyManager.getTrustedPublicKey(voucher.issuerKeyId)
        if (issuerPubKey == null) {
            return DecryptResult.fail("未知签发者: " + voucher.issuerKeyId)
        }
        
        // [2] 重建签名数据并验证 ECDSA 签名
        ephemeralPubKey = decodePublicKeyFromBase64(voucher.ephemeralPublicKey)
        encryptedData = Base64.getDecoder().decode(voucher.encryptedData)
        iv = Base64.getDecoder().decode(voucher.iv)
        
        signData = concat(
            ephemeralPubKey.getEncoded(),
            encryptedData,
            iv,
            voucher.playerUuid.toString().getBytes(UTF_8),
            longToBytes(voucher.timestamp),
            hexDecode(voucher.itemHash)
        )
        
        if (!ecdsaVerify(issuerPubKey, SHA256(signData), 
                         Base64.getDecoder().decode(voucher.signature))) {
            return DecryptResult.fail("签名验证失败（数据可能被篡改）")
        }
        
        // [3] TTL 过期检查
        elapsed = Instant.now().getEpochSecond() - voucher.timestamp
        if (elapsed > voucher.ttl) {
            return DecryptResult.fail("凭据已过期（TTL: " + voucher.ttl + "秒，已过: " + elapsed + "秒）")
        }
        
        // [4] 玩家 UUID 绑定检查
        if (Config.REQUIRE_PLAYER_MATCH.get() && voucher.playerUuid != currentPlayerUuid) {
            return DecryptResult.fail("凭据与当前玩家不匹配")
        }
        
        // [5] 一次性检查（黑名单）
        voucherId = computeVoucherId(voucher)
        if (blacklist.isRedeemed(voucherId)) {
            return DecryptResult.fail("凭据已使用（双花检测）")
        }
        
        // [6] ECDH 密钥协商（B 侧）
        sharedSecret = ecdhKeyAgreement(
            localKeyPair.getPrivate(),  // B 的长期私钥
            ephemeralPubKey             // A 的临时公钥
        )
        
        // [7] HKDF 密钥派生（参数与加密时完全相同）
        salt = ephemeralPubKey.getEncoded()
        aesKey = HkdfUtil.deriveKey(salt, sharedSecret, HKDF_INFO, 32)
        
        // [8] AES-256-GCM 解密
        try {
            plaintextNbt = aesGcmDecrypt(aesKey, iv, encryptedData)
        } catch (AEADBadTagException e) {
            return DecryptResult.fail("解密失败（密钥错误或数据损坏）")
        }
        
        // [9] 验证 itemHash
        computedHash = SHA256(plaintextNbt)
        computedHashTruncated = hexEncode(Arrays.copyOfRange(computedHash, 0, 8))
        if (computedHashTruncated != voucher.itemHash) {
            return DecryptResult.fail("数据完整性校验失败")
        }
        
        // [10] 记录到黑名单（防双花）
        blacklist.redeem(voucherId, voucher.issuerKeyId, voucher.playerUuid)
        
        return DecryptResult.success(plaintextNbt, voucher)
    }
    
    // ─── 底层加密操作 ───
    
    static KeyPair generateEphemeralKeyPair() {
        gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(new ECGenParameterSpec("secp256r1"), SecureRandom.getInstanceStrong())
        return gen.generateKeyPair()
    }
    
    static byte[] ecdhKeyAgreement(PrivateKey myPrivate, PublicKey theirPublic) {
        ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(theirPublic, true)
        return ka.generateSecret()
    }
    
    static byte[] ecdsaSign(PrivateKey privateKey, byte[] data) {
        sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey, SecureRandom.getInstanceStrong())
        sig.update(data)
        return sig.sign()
    }
    
    static boolean ecdsaVerify(PublicKey publicKey, byte[] data, byte[] signature) {
        sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(data)
        return sig.verify(signature)
    }
    
    static byte[] aesGcmEncrypt(SecretKey key, byte[] iv, byte[] plaintext) {
        cipher = Cipher.getInstance("AES/GCM/NoPadding")
        gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        return cipher.doFinal(plaintext)
    }
    
    static byte[] aesGcmDecrypt(SecretKey key, byte[] iv, byte[] ciphertext) {
        cipher = Cipher.getInstance("AES/GCM/NoPadding")
        gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        return cipher.doFinal(ciphertext)  // 失败时抛出 AEADBadTagException
    }
    
    static String computeVoucherId(Voucher voucher) {
        // voucherId = SHA256(ephemeralPublicKey + timestamp)
        data = concat(
            Base64.getDecoder().decode(voucher.ephemeralPublicKey),
            longToBytes(voucher.timestamp)
        )
        return hexEncode(SHA256(data))
    }
    
    // ─── 解密结果 ───
    
    record DecryptResult(
        boolean success,
        byte[] plaintextNbt,      // 成功时的解密数据
        Voucher voucher,          // 原始凭据
        String failureReason      // 失败原因
    ) {
        static DecryptResult success(byte[] nbt, Voucher v) {
            return new DecryptResult(true, nbt, v, null)
        }
        static DecryptResult fail(String reason) {
            return new DecryptResult(false, null, null, reason)
        }
    }
}
```

---

## 6. Voucher.java — 凭据数据结构

```
// Voucher 是一个纯数据结构（record），对应 JSON 文件格式
record Voucher(
    int version,                 // 协议版本（当前 = 2）
    String issuerKeyId,          // 签发者 KeyID（SHA256(pubKey) 前8字节Hex）
    String ephemeralPublicKey,   // Base64(DER X.509) — 临时公钥
    String encryptedData,        // Base64(AES-GCM 密文)
    String iv,                   // Base64(12字节 IV)
    UUID playerUuid,             // 导出玩家 UUID
    long timestamp,              // Unix 时间戳（秒）
    String itemHash,             // SHA256(明文NBT) 前8字节Hex
    int ttl,                     // 有效期（秒）
    String signature             // Base64(ECDSA 签名)
) {
    
    // ─── JSON 序列化（Gson）───
    
    static Voucher fromJson(String json) {
        // 自定义 TypeAdapter 处理 UUID
        gson = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new UuidAdapter())
            .create()
        return gson.fromJson(json, Voucher.class)
    }
    
    String toJson() {
        gson = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new UuidAdapter())
            .setPrettyPrinting()
            .create()
        return gson.toJson(this)
    }
    
    // ─── 文件 I/O ───
    
    static Voucher fromFile(Path file) {
        json = Files.readString(file)
        return fromJson(json)
    }
    
    void toFile(Path file) {
        Files.writeString(file, toJson())
    }
    
    // 生成文件名
    String suggestedFilename() {
        randomSuffix = hexEncode(SecureRandom.getBytes(4))
        return "export_" + timestamp + "_" + randomSuffix + ".json"
    }
    
    // ─── UUID TypeAdapter ───
    
    class UuidAdapter extends TypeAdapter<UUID> {
        void write(JsonWriter out, UUID value) {
            out.value(value.toString())
        }
        UUID read(JsonReader in) {
            return UUID.fromString(in.nextString())
        }
    }
}
```

---

## 7. VoucherBlacklist.java — SQLite 黑名单

```
class VoucherBlacklist {
    
    Path dbPath           // config/sneakernet/blacklist.db
    Connection connection // SQLite 连接
    
    // ─── 初始化 ───
    
    void initialize() {
        // 确保目录存在
        dbPath.parent.createDirectories()
        
        // 打开连接（WAL 模式，支持并发读）
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)
        connection.createStatement().execute("PRAGMA journal_mode=WAL")
        
        // 创建表
        connection.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS blacklist (
                voucher_id   TEXT PRIMARY KEY,    -- SHA256(ephemeralPubKey + timestamp) Hex
                issuer_id    TEXT NOT NULL,       -- 签发者 KeyID
                player_uuid  TEXT NOT NULL,       -- 使用者 UUID
                used_at      INTEGER NOT NULL,    -- 核销时间（Unix秒）
                ttl_seconds  INTEGER NOT NULL     -- 原始 TTL（用于清理）
            )
        """)
        
        connection.createStatement().execute("""
            CREATE INDEX IF NOT EXISTS idx_player 
            ON blacklist(player_uuid)
        """)
        
        connection.createStatement().execute("""
            CREATE INDEX IF NOT EXISTS idx_used_at 
            ON blacklist(used_at)
        """)
    }
    
    // ─── 查询 ───
    
    boolean isRedeemed(String voucherId) {
        stmt = connection.prepareStatement(
            "SELECT 1 FROM blacklist WHERE voucher_id = ?")
        stmt.setString(1, voucherId)
        rs = stmt.executeQuery()
        return rs.next()  // 存在 = 已核销
    }
    
    // ─── 记录核销 ───
    
    void redeem(String voucherId, String issuerId, UUID playerUuid, int ttl) {
        stmt = connection.prepareStatement("""
            INSERT INTO blacklist(voucher_id, issuer_id, player_uuid, used_at, ttl_seconds)
            VALUES(?, ?, ?, ?, ?)
        """)
        stmt.setString(1, voucherId)
        stmt.setString(2, issuerId)
        stmt.setString(3, playerUuid.toString())
        stmt.setLong(4, Instant.now().getEpochSecond())
        stmt.setInt(5, ttl)
        stmt.executeUpdate()
    }
    
    // ─── 过期记录清理 ───
    
    void cleanExpired() {
        // 删除 used_at + ttl_seconds < 当前时间 的记录
        // （已过 TTL 期的记录不再有双花风险）
        now = Instant.now().getEpochSecond()
        stmt = connection.prepareStatement("""
            DELETE FROM blacklist WHERE (used_at + ttl_seconds) < ?
        """)
        stmt.setLong(1, now)
        int deleted = stmt.executeUpdate()
        if (deleted > 0) {
            LOGGER.debug("已清理 {} 条过期黑名单记录", deleted)
        }
    }
    
    // ─── 关闭 ───
    
    void close() {
        if (connection != null) connection.close()
    }
}
```

---

## 8. ItemNbtUtil.java — NBT 序列化工具

```
class ItemNbtUtil {
    
    // ─── 容器 NBT → 二进制（用于加密）───
    
    static byte[] serializeContainerToBytes(Container container) {
        buf = new FriendlyByteBuf(Unpooled.buffer())
        
        // 写入容器类型标识
        if (container instanceof ChestBlockEntity) {
            buf.writeUtf("minecraft:chest")
        } else if (container instanceof ShulkerBoxBlockEntity) {
            buf.writeUtf("minecraft:shulker_box")
        } else if (container instanceof BarrelBlockEntity) {
            buf.writeUtf("minecraft:barrel")
        } else {
            buf.writeUtf("minecraft:container")  // 通用容器
        }
        
        // 写入容器自定义名称（如有）
        customName = container.getCustomName()
        buf.writeBoolean(customName != null)
        if (customName != null) {
            buf.writeUtf(Component.Serializer.toJson(customName))
        }
        
        // 写入物品数量
        items = getAllItems(container)
        buf.writeVarInt(items.size())
        
        // 逐个写入物品 NBT
        for (item : items) {
            tag = new CompoundTag()
            item.save(tag)  // ItemStack.save() → 包含 id、count、components
            buf.writeNbt(tag)
        }
        
        return buf.array()
    }
    
    // ─── 二进制 → 容器 NBT（用于解密后还原）───
    
    static ContainerNbtData deserializeContainerFromBytes(byte[] data) {
        buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        
        // 读取容器类型
        containerType = buf.readUtf()
        
        // 读取自定义名称
        hasCustomName = buf.readBoolean()
        customName = hasCustomName ? Component.Serializer.fromJson(buf.readUtf()) : null
        
        // 读取物品列表
        itemCount = buf.readVarInt()
        items = new ArrayList<ItemStack>()
        for (i = 0; i < itemCount; i++) {
            tag = buf.readNbt()
            item = ItemStack.parseOptional(RegistryAccess, tag)
            items.add(item)
        }
        
        return new ContainerNbtData(containerType, customName, items)
    }
    
    // ─── 递归扫描容器 ───
    
    static List<ItemStack> getAllItems(Container container) {
        items = new ArrayList<ItemStack>()
        for (slot = 0; slot < container.getContainerSize(); slot++) {
            stack = container.getItem(slot)
            if (!stack.isEmpty()) {
                items.add(stack.copy())  // 复制，避免修改原容器
            }
        }
        return items
    }
    
    // ─── 数据类 ───
    
    record ContainerNbtData(
        String containerType,       // 方块类型 ID
        Component customName,       // 自定义名称（可为 null）
        List<ItemStack> items       // 物品列表
    )
}
```

---

## 9. ModItems.java — 物品注册

```
class ModItems {
    
    static final DeferredRegister<Item> ITEMS = 
        DeferredRegister.create(Registries.ITEM, SneakerNet.MOD_ID)
    
    // ─── 物品注册 ───
    
    // SneakerNet Ticket（票据）— 可合成，右键容器导出
    static final DeferredItem<TicketItem> TICKET = ITEMS.register(
        "sneakernet_ticket",
        () -> new TicketItem(new Item.Properties()
            .stacksTo(64)             // 可堆叠64个
            .rarity(Rarity.UNCOMMON)  // 绿色文字
        )
    )
    
    // SneakerNet Package（包裹）— 不可合成，import后获得，右键放置容器
    static final DeferredItem<PackageItem> PACKAGE = ITEMS.register(
        "sneakernet_package",
        () -> new PackageItem(new Item.Properties()
            .stacksTo(1)              // 不可堆叠（每个包裹独立数据）
            .rarity(Rarity.RARE)     // 蓝色文字
        )
    )
    
    // ─── 注册事件总线 ───
    
    static void register() {
        ITEMS.register(Bus.MOD)
    }
}
```

---

## 10. TicketItem.java — 票据物品行为

```
class TicketItem extends Item {
    
    constructor(Properties props) {
        super(props)
    }
    
    // ─── 右键交互（核心逻辑）───
    
    @Override
    InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS  // 仅服务端
        
        // [1] 获取玩家瞄准的方块
        hitResult = player.pick(5.0, 0, false)  // 射线追踪距离 5 格
        if (hitResult.getType() != BLOCK) {
            return InteractionResult.PASS  // 没瞄准方块
        }
        
        // [2] 检查目标方块是否是容器
        blockPos = hitResult.getBlockPos()
        blockState = level.getBlockState(blockPos)
        blockEntity = level.getBlockEntity(blockPos)
        
        if (!(blockEntity instanceof Container)) {
            player.sendSystemMessage(Component.translatable(
                "sneakernet.ticket.not_container"))
            return InteractionResult.FAIL
        }
        
        // [3] 检查是否有可信服务器（至少需要一个目标服务器）
        keyManager = SneakerNet.keyManager
        if (keyManager.trustedServers.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                "sneakernet.ticket.no_trusted_servers"))
            return InteractionResult.FAIL
        }
        
        // [4] 先扣除 1 个 Ticket（防止重复触发）
        ticketStack = player.getItemInHand(hand)
        ticketStack.shrink(1)
        if (ticketStack.isEmpty()) {
            player.setItemInHand(hand, ItemStack.EMPTY)
        }
        
        // [5] 异步执行导出
        container = (Container) blockEntity
        playerUuid = player.getUUID()
        server = (MinecraftServer) level.getServer()
        
        SneakerNet.cryptoExecutor.supplyAsync(() -> {
            return doExport(container, keyManager, playerUuid)
        }).thenAccept(result -> {
            // 切回主线程通知
            server.execute(() -> {
                if (result.success) {
                    player.sendSystemMessage(Component.translatable(
                        "sneakernet.ticket.export_success",
                        result.voucherFile.getFileName()
                    ))
                } else {
                    // 导出失败，退还 Ticket
                    player.getInventory().add(new ItemStack(ModItems.TICKET.get(), 1))
                    player.sendSystemMessage(Component.translatable(
                        "sneakernet.ticket.export_failed",
                        result.failureReason
                    ))
                }
            })
        })
        
        return InteractionResult.CONSUME
    }
    
    // ─── 导出逻辑（在线程池中执行）───
    
    static ExportResult doExport(Container container, KeyManager keyManager, UUID playerUuid) {
        try {
            // [a] 序列化容器 NBT
            nbtBytes = ItemNbtUtil.serializeContainerToBytes(container)
            
            // [b] 选择目标服务器（当前版本：对每个可信服务器各生成一份凭据）
            //     Phase 1 简化：默认导出给所有可信服务器
            //     Phase 2：让玩家选择目标服务器
            voucherFiles = new ArrayList<Path>()
            
            for (trustedServer : keyManager.trustedServers.values()) {
                targetPubKey = keyManager.decodePublicKeyFromBase64(
                    trustedServer.pubKeyBase64)
                
                // [c] 加密 + 签名
                ttlSeconds = Config.VOUCHER_TTL_HOURS.get() * 3600
                voucher = VoucherCrypto.encryptAndSign(
                    keyManager.localKeyPair,
                    targetPubKey,
                    nbtBytes,
                    playerUuid,
                    ttlSeconds
                )
                
                // [d] 保存到客户端目录
                voucherDir = gameDir.resolve("sneakernet/vouchers/")
                voucherDir.createDirectories()
                
                fileName = trustedServer.name + "_" + voucher.suggestedFilename()
                voucherFile = voucherDir.resolve(fileName)
                voucher.toFile(voucherFile)
                voucherFiles.add(voucherFile)
            }
            
            return ExportResult.success(voucherFiles)
        } catch (Exception e) {
            LOGGER.error("导出失败", e)
            return ExportResult.fail(e.getMessage())
        }
    }
    
    // ─── 导出结果 ───
    
    record ExportResult(
        boolean success,
        List<Path> voucherFiles,
        String failureReason
    ) {
        static ExportResult success(List<Path> files) {
            return new ExportResult(true, files, null)
        }
        static ExportResult fail(String reason) {
            return new ExportResult(false, null, reason)
        }
    }
}
```

---

## 11. PackageItem.java — 包裹物品行为

```
class PackageItem extends Item {
    
    constructor(Properties props) {
        super(props)
    }
    
    // ─── 右键交互（核心逻辑）───
    
    @Override
    InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS  // 仅服务端
        
        // [1] 读取 Package NBT 中的容器数据
        packageStack = player.getItemInHand(hand)
        packageTag = packageStack.getOrCreateTag()
        
        if (!packageTag.contains("sneakernet:package")) {
            player.sendSystemMessage(Component.translatable(
                "sneakernet.package.no_data"))
            return InteractionResult.FAIL
        }
        
        dataTag = packageTag.getCompound("sneakernet:package")
        
        // [2] 解析容器数据
        containerType = dataTag.getString("containerType")
        customName = dataTag.contains("customName") 
            ? Component.Serializer.fromJson(dataTag.getString("customName"))
            : null
        itemsNbt = dataTag.getCompound("items")
        
        // [3] 确定放置位置
        hitResult = player.pick(5.0, 0, false)
        if (hitResult.getType() == BLOCK) {
            // 右键方块 → 在方块邻面放置容器
            placePos = hitResult.getBlockPos().relative(hitResult.getDirection())
        } else {
            // 右键空气 → 在玩家脚下放置容器
            placePos = player.blockPosition()
        }
        
        // [4] 检查放置位置是否可用
        if (!level.getBlockState(placePos).isAir()) {
            player.sendSystemMessage(Component.translatable(
                "sneakernet.package.no_space"))
            return InteractionResult.FAIL
        }
        
        // [5] 放置容器方块
        containerBlock = getContainerBlock(containerType)
        if (containerBlock == null) {
            player.sendSystemMessage(Component.translatable(
                "sneakernet.package.unknown_container", containerType))
            return InteractionResult.FAIL
        }
        
        level.setBlockAndUpdate(placePos, containerBlock.defaultBlockState())
        
        // [6] 填入物品
        blockEntity = level.getBlockEntity(placePos)
        if (blockEntity instanceof Container container) {
            for (slot = 0; slot < container.getContainerSize(); slot++) {
                itemTag = itemsNbt.getCompound(String.valueOf(slot))
                if (!itemTag.isEmpty()) {
                    item = ItemStack.parseOptional(level.registryAccess(), itemTag)
                    container.setItem(slot, item)
                }
            }
            // 设置自定义名称
            if (customName != null) {
                blockEntity.setCustomName(customName)
            }
            blockEntity.setChanged()
        }
        
        // [7] 消耗 Package 物品
        packageStack.shrink(1)
        if (packageStack.isEmpty()) {
            player.setItemInHand(hand, ItemStack.EMPTY)
        }
        
        // [8] 通知玩家
        player.sendSystemMessage(Component.translatable(
            "sneakernet.package.placed", containerType))
        
        return InteractionResult.CONSUME
    }
    
    // ─── 创建 Package 物品实例 ───
    
    static ItemStack createPackage(Voucher voucher, byte[] plaintextNbt) {
        // 从解密后的 NBT 字节创建 Package 物品
        containerData = ItemNbtUtil.deserializeContainerFromBytes(plaintextNbt)
        
        packageStack = new ItemStack(ModItems.PACKAGE.get())
        tag = packageStack.getOrCreateTag()
        
        dataTag = new CompoundTag()
        dataTag.putInt("version", voucher.version)
        dataTag.putString("issuerKeyId", voucher.issuerKeyId)
        dataTag.putString("playerUuid", voucher.playerUuid.toString())
        dataTag.putLong("exportedAt", voucher.timestamp)
        dataTag.putString("containerType", containerData.containerType)
        
        if (containerData.customName != null) {
            dataTag.putString("customName", Component.Serializer.toJson(containerData.customName))
        }
        
        // 写入物品列表
        itemsTag = new CompoundTag()
        for (i = 0; i < containerData.items.size(); i++) {
            itemTag = new CompoundTag()
            containerData.items.get(i).save(itemTag)
            itemsTag.put(String.valueOf(i), itemTag)
        }
        dataTag.put("items", itemsTag)
        
        tag.put("sneakernet:package", dataTag)
        
        // 设置显示名称
        packageStack.set(DataComponents.CUSTOM_NAME, Component.translatable(
            "sneakernet.package.name", containerData.containerType))
        
        return packageStack
    }
    
    // ─── 工具方法 ───
    
    static Block getContainerBlock(String containerType) {
        return switch (containerType) {
            case "minecraft:chest"       -> Blocks.CHEST
            case "minecraft:shulker_box" -> Blocks.SHULKER_BOX  // 需处理颜色
            case "minecraft:barrel"      -> Blocks.BARREL
            case "minecraft:trapped_chest" -> Blocks.TRAPPED_CHEST
            default -> null
        }
    }
}
```

---

## 12. SneakerNetCommands.java — Brigadier 命令

```
class SneakerNetCommands {
    
    // ─── 注册命令树 ───
    
    static void register() {
        RegisterCommandsEvent.addListener((event) -> {
            dispatcher = event.getDispatcher()
            
            // /sneakernet
            root = Commands.literal("sneakernet")
                .requires(src -> src.hasPermission(0))  // 所有玩家可用
            
            // ├── keygen（仅 OP）
            root.then(Commands.literal("keygen")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> executeKeygen(ctx)))
            
            // ├── showpub（所有玩家）
            root.then(Commands.literal("showpub")
                .requires(src -> src.hasPermission(0))
                .executes(ctx -> executeShowpub(ctx)))
            
            // ├── trust（仅 OP）
            root.then(Commands.literal("trust")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("file", StringArgumentType.string())
                        .executes(ctx -> executeTrust(ctx)))))
            
            // ├── untrust（仅 OP）
            root.then(Commands.literal("untrust")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> executeUntrust(ctx))))
            
            // ├── list（仅 OP）
            root.then(Commands.literal("list")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> executeList(ctx)))
            
            // └── import（所有玩家）
            root.then(Commands.literal("import")
                .requires(src -> src.hasPermission(0))
                .executes(ctx -> executeImportAll(ctx))  // 不指定文件 → 导入全部
                .then(Commands.argument("filename", StringArgumentType.string())
                    .executes(ctx -> executeImportOne(ctx))))  // 指定文件名
            
            dispatcher.register(root)
        })
    }
    
    // ─── /sneakernet keygen ───
    
    static int executeKeygen(CommandContext ctx) {
        source = ctx.getSource()
        keyManager = SneakerNet.keyManager
        
        // 重新生成密钥对
        keyManager.localKeyPair = keyManager.generateKeyPair()
        keyManager.saveKeyPair(
            keyManager.configDir.resolve("local_key.der"),
            keyManager.configDir.resolve("local_pub.der")
        )
        
        keyManager.localKeyId = keyManager.computeKeyId(keyManager.localKeyPair.getPublic())
        keyManager.localFingerprint = keyManager.computeFingerprint(keyManager.localKeyPair.getPublic())
        
        source.sendSuccess(() -> Component.translatable(
            "sneakernet.keygen.success", keyManager.localKeyId), true)
        
        return 1
    }
    
    // ─── /sneakernet showpub ───
    
    static int executeShowpub(CommandContext ctx) {
        source = ctx.getSource()
        keyManager = SneakerNet.keyManager
        
        // 保存公钥 JSON 文件
        serverName = source.getServer().getServerModName()  // 或自定义
        pubJsonPath = keyManager.exportPublicKeyJson(serverName)
        
        source.sendSuccess(() -> Component.translatable(
            "sneakernet.showpub.success", pubJsonPath.toString()), false)
        
        // 同时显示指纹（方便管理员口头验证）
        source.sendSuccess(() -> Component.translatable(
            "sneakernet.showpub.fingerprint", keyManager.localFingerprint), false)
        
        return 1
    }
    
    // ─── /sneakernet trust <name> <file> ───
    
    static int executeTrust(CommandContext ctx) {
        source = ctx.getSource()
        name = StringArgumentType.getString(ctx, "name")
        fileStr = StringArgumentType.getString(ctx, "file")
        keyManager = SneakerNet.keyManager
        
        // 解析文件路径（相对于 config/sneakernet/）
        pubJsonFile = keyManager.configDir.resolve(fileStr)
        if (!pubJsonFile.exists()) {
            source.sendFailure(Component.translatable(
                "sneakernet.trust.file_not_found", fileStr))
            return 0
        }
        
        // 添加可信服务器
        keyManager.addTrustedServer(name, pubJsonFile)
        
        source.sendSuccess(() -> Component.translatable(
            "sneakernet.trust.success", name), true)
        
        return 1
    }
    
    // ─── /sneakernet untrust <name> ───
    
    static int executeUntrust(CommandContext ctx) {
        source = ctx.getSource()
        name = StringArgumentType.getString(ctx, "name")
        keyManager = SneakerNet.keyManager
        
        if (keyManager.removeTrustedServer(name)) {
            source.sendSuccess(() -> Component.translatable(
                "sneakernet.untrust.success", name), true)
            return 1
        } else {
            source.sendFailure(Component.translatable(
                "sneakernet.untrust.not_found", name))
            return 0
        }
    }
    
    // ─── /sneakernet list ───
    
    static int executeList(CommandContext ctx) {
        source = ctx.getSource()
        keyManager = SneakerNet.keyManager
        
        if (keyManager.trustedServers.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                "sneakernet.list.empty"), false)
            return 0
        }
        
        // 列出所有可信服务器
        for (server : keyManager.trustedServers.values()) {
            source.sendSuccess(() -> Component.literal(
                "  " + server.name + " (KeyID: " + server.keyId + 
                ", Fingerprint: " + server.fingerprint + ")"), false)
        }
        
        return keyManager.trustedServers.size()
    }
    
    // ─── /sneakernet import（全部）───
    
    static int executeImportAll(CommandContext ctx) {
        source = ctx.getSource()
        player = source.getPlayerOrException()
        
        // 扫描客户端目录下所有凭证文件
        vouchersDir = gameDir.resolve("sneakernet/vouchers/")
        voucherFiles = vouchersDir.listFiles("*.json")
        
        if (voucherFiles.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                "sneakernet.import.no_vouchers"), false)
            return 0
        }
        
        doImportVouchers(player, voucherFiles)
        return voucherFiles.size()
    }
    
    // ─── /sneakernet import <filename> ───
    
    static int executeImportOne(CommandContext ctx) {
        source = ctx.getSource()
        player = source.getPlayerOrException()
        filename = StringArgumentType.getString(ctx, "filename")
        
        voucherFile = gameDir.resolve("sneakernet/vouchers/" + filename)
        if (!voucherFile.exists()) {
            source.sendFailure(Component.translatable(
                "sneakernet.import.file_not_found", filename))
            return 0
        }
        
        doImportVouchers(player, [voucherFile])
        return 1
    }
    
    // ─── 导入核心逻辑 ───
    
    static void doImportVouchers(Player player, List<Path> voucherFiles) {
        keyManager = SneakerNet.keyManager
        localKeyPair = keyManager.localKeyPair
        blacklist = SneakerNet.blacklist
        server = player.getServer()
        
        // 异步执行解密
        SneakerNet.cryptoExecutor.supplyAsync(() -> {
            successCount = 0
            failCount = 0
            failReasons = new ArrayList<String>()
            packages = new ArrayList<ItemStack>()
            
            for (file : voucherFiles) {
                try {
                    voucher = Voucher.fromFile(file)
                    result = VoucherCrypto.decryptAndVerify(
                        localKeyPair, voucher, keyManager, 
                        player.getUUID(), blacklist
                    )
                    
                    if (result.success()) {
                        // 创建 Package 物品
                        packageStack = PackageItem.createPackage(voucher, result.plaintextNbt())
                        packages.add(packageStack)
                        successCount++
                        
                        // 移动凭证到已核销目录
                        redeemedDir = gameDir.resolve("sneakernet/redeemed/")
                        redeemedDir.createDirectories()
                        Files.move(file, redeemedDir.resolve(file.getFileName()))
                        
                    } else {
                        failCount++
                        failReasons.add(file.getFileName() + ": " + result.failureReason())
                    }
                } catch (Exception e) {
                    failCount++
                    failReasons.add(file.getFileName() + ": 解析失败")
                    LOGGER.error("导入凭证失败: " + file, e)
                }
            }
            
            return new ImportResult(successCount, failCount, failReasons, packages)
            
        }).thenAccept(result -> {
            // 切回主线程
            server.execute(() -> {
                // 发放 Package 物品
                for (pkg : result.packages()) {
                    if (!player.getInventory().add(pkg)) {
                        // 背包满了 → 掉落到玩家位置
                        player.drop(pkg, false)
                    }
                }
                
                // 显示结果
                player.sendSystemMessage(Component.translatable(
                    "sneakernet.import.result",
                    result.successCount(),
                    result.failCount()
                ))
                
                if (!result.failReasons().isEmpty()) {
                    for (reason : result.failReasons()) {
                        player.sendSystemMessage(Component.literal("  ✗ " + reason)
                            .withStyle(ChatFormatting.RED))
                    }
                }
            })
        })
    }
    
    // ─── 导入结果 ───
    
    record ImportResult(
        int successCount,
        int failCount,
        List<String> failReasons,
        List<ItemStack> packages
    )
}
```

---

## 13. CryptoExecutor.java — 多线程管理

```
class CryptoExecutor {
    
    // ─── 线程池 ───
    ExecutorService executor
    
    constructor() {
        executor = Executors.newFixedThreadPool(2, r -> {
            t = new Thread(r, "SneakerNet-Crypto-Worker")
            t.setDaemon(true)   // 守护线程，不阻止 JVM 退出
            t.setPriority(Thread.NORM_PRIORITY - 1)  // 略低于主线程
            return t
        })
    }
    
    // ─── 异步执行 ───
    
    <T> CompletableFuture<T> supplyAsync(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call()
            } catch (Exception e) {
                throw new CompletionException(e)
            }
        }, executor)
    }
    
    // ─── 关闭 ───
    
    void shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                LOGGER.warn("加密线程池未能在10秒内优雅关闭，已强制终止")
            }
        } catch (InterruptedException e) {
            executor.shutdownNow()
        }
    }
}
```

---

## 14. SneakerNetClient.java — 客户端入口

```
class SneakerNetClient {
    
    // Phase 1：客户端不需要任何特殊逻辑
    // 凭证文件由服务端指令直接操作（写入 .minecraft/sneakernet/）
    // Package 物品的 NBT 在服务端生成，客户端只负责渲染
    
    @SubscribeEvent
    onClientSetup(FMLClientSetupEvent event) {
        // Phase 1：留空
        // Phase 2：注册客户端文件选择器、GUI 等
    }
}
```

---

## 15. 合成配方（JSON）

```
// data/sneakernet/recipes/sneakernet_ticket.json
{
    "type": "minecraft:crafting_shaped",
    "pattern": [
        "PPP",
        "PSP",
        "PPP"
    ],
    "key": {
        "P": { "item": "minecraft:paper" },
        "S": { "item": "minecraft:ink_sac" }
    },
    "result": {
        "id": "sneakernet:sneakernet_ticket",
        "count": 8
    }
}
```

---

## 16. 语言文件

```
// assets/sneakernet/lang/en_us.json
{
    "sneakernet.ticket.not_container": "§cTarget is not a container!",
    "sneakernet.ticket.no_trusted_servers": "§cNo trusted servers configured. Ask an admin to add one.",
    "sneakernet.ticket.export_success": "§aExport successful! File saved to sneakernet/vouchers/%s",
    "sneakernet.ticket.export_failed": "§cExport failed: %s",
    "sneakernet.package.no_data": "§cThis package has no data!",
    "sneakernet.package.no_space": "§cNo space to place container!",
    "sneakernet.package.unknown_container": "§cUnknown container type: %s",
    "sneakernet.package.placed": "§aContainer placed: %s",
    "sneakernet.package.name": "SneakerNet Package (%s)",
    "sneakernet.keygen.success": "§aKey pair generated. KeyID: %s",
    "sneakernet.showpub.success": "§aPublic key saved to: %s",
    "sneakernet.showpub.fingerprint": "§7Fingerprint: %s",
    "sneakernet.trust.success": "§aTrusted server added: %s",
    "sneakernet.trust.file_not_found": "§cFile not found: %s",
    "sneakernet.untrust.success": "§aRemoved trusted server: %s",
    "sneakernet.untrust.not_found": "§cServer not found: %s",
    "sneakernet.list.empty": "§7No trusted servers configured.",
    "sneakernet.import.no_vouchers": "§7No voucher files found.",
    "sneakernet.import.file_not_found": "§cFile not found: %s",
    "sneakernet.import.result": "§aImport complete: %d succeeded, %d failed",
    "item.sneakernet.sneakernet_ticket": "SneakerNet Ticket",
    "item.sneakernet.sneakernet_package": "SneakerNet Package"
}

// assets/sneakernet/lang/zh_cn.json
{
    "sneakernet.ticket.not_container": "§c目标不是容器！",
    "sneakernet.ticket.no_trusted_servers": "§c没有配置可信服务器，请联系管理员添加。",
    "sneakernet.ticket.export_success": "§a导出成功！文件已保存至 sneakernet/vouchers/%s",
    "sneakernet.ticket.export_failed": "§c导出失败：%s",
    "sneakernet.package.no_data": "§c此包裹没有数据！",
    "sneakernet.package.no_space": "§c没有空间放置容器！",
    "sneakernet.package.unknown_container": "§c未知容器类型：%s",
    "sneakernet.package.placed": "§a容器已放置：%s",
    "sneakernet.package.name": "SneakerNet 包裹（%s）",
    "sneakernet.keygen.success": "§a密钥对已生成。KeyID：%s",
    "sneakernet.showpub.success": "§a公钥已保存至：%s",
    "sneakernet.showpub.fingerprint": "§7指纹：%s",
    "sneakernet.trust.success": "§a已添加可信服务器：%s",
    "sneakernet.trust.file_not_found": "§c文件未找到：%s",
    "sneakernet.untrust.success": "§a已移除可信服务器：%s",
    "sneakernet.untrust.not_found": "§c未找到服务器：%s",
    "sneakernet.list.empty": "§7暂无已配置的可信服务器。",
    "sneakernet.import.no_vouchers": "§7未找到凭证文件。",
    "sneakernet.import.file_not_found": "§c文件未找到：%s",
    "sneakernet.import.result": "§a导入完成：%d 个成功，%d 个失败",
    "item.sneakernet.sneakernet_ticket": "SneakerNet 票据",
    "item.sneakernet.sneakernet_package": "SneakerNet 包裹"
}
```

---

## 17. neoforge.mods.toml

```
modId = "sneakernet"
version = "1.0.0"
displayName = "SneakerNet"
description = '''Offline cross-server item transfer using ECC encryption.
Players carry encrypted voucher files between servers as a "sneaker network".'''
authors = "yansunsky"
license = "MIT"

# NeoForge 依赖
[[dependencies.sneakernet]]
    modId = "neoforge"
    type = "required"
    versionRange = "[21.1,)"
    ordering = "NONE"
    side = "BOTH"

[[dependencies.sneakernet]]
    modId = "minecraft"
    type = "required"
    versionRange = "[1.21.1,1.21.2)"
    ordering = "NONE"
    side = "BOTH"
```

---

## 附录 A：完整交互流程时序

```
┌──────────┐      ┌──────────┐      ┌──────────┐      ┌──────────┐
│  玩家 A   │      │ 服务器 A  │      │  文件系统  │      │ 服务器 B  │
└────┬─────┘      └────┬─────┘      └────┬─────┘      └────┬─────┘
     │                  │                 │                 │
     │  右键容器(Ticket)│                 │                 │
     │─────────────────>│                 │                 │
     │                  │  扣除1个Ticket   │                 │
     │                  │  扫描容器NBT     │                 │
     │                  │  ───异步开始───  │                 │
     │                  │  生成临时ECC密钥 │                 │
     │                  │  ECDH协商        │                 │
     │                  │  HKDF派生        │                 │
     │                  │  AES-GCM加密     │                 │
     │                  │  ECDSA签名       │                 │
     │                  │  ───异步结束───  │                 │
     │                  │                 │                 │
     │                  │  保存voucher.json│                 │
     │                  │────────────────>│                 │
     │                  │                 │                 │
     │  "导出成功！"    │                 │                 │
     │<─────────────────│                 │                 │
     │                  │                 │                 │
     │  ═══ 玩家手动拷贝文件 ═══         │                 │
     │  (U盘/网盘/直接复制)              │                 │
     │                  │                 │                 │
     │  /sneakernet import               │                 │
     │──────────────────────────────────────────────────────>│
     │                  │                 │                 │
     │                  │                 │  读取voucher.json
     │                  │                 │<────────────────│
     │                  │                 │                 │
     │                  │                 │  ───异步开始───│
     │                  │                 │  验证签名      │
     │                  │                 │  ECDH协商       │
     │                  │                 │  HKDF派生       │
     │                  │                 │  AES-GCM解密    │
     │                  │                 │  检查黑名单     │
     │                  │                 │  检查TTL       │
     │                  │                 │  记录黑名单     │
     │                  │                 │  ───异步结束───│
     │                  │                 │                 │
     │                  │                 │  移动到redeemed/│
     │                  │                 │<────────────────│
     │                  │                 │                 │
     │  获得Package物品 │                 │                 │
     │<──────────────────────────────────────────────────────│
     │                  │                 │                 │
     │  右键放置Package │                 │                 │
     │──────────────────────────────────────────────────────>│
     │                  │                 │  放置容器方块  │
     │                  │                 │  填入物品       │
     │  "容器已放置！"  │                 │                 │
     │<──────────────────────────────────────────────────────│
```

---

## 附录 B：安全检查清单（导入时）

```
Voucher 导入时，按以下顺序检查：

1. ✅ JSON 解析       → 格式是否合法
2. ✅ 版本号检查      → version == 2
3. ✅ 签发者查找      → issuerKeyId 是否在 trusted_keys.json 中
4. ✅ ECDSA 签名验证  → 数据是否被篡改
5. ✅ ECDH + AES 解密 → 密文是否可解
6. ✅ itemHash 校验   → 明文是否被替换
7. ✅ TTL 过期检查    → 凭据是否超时
8. ✅ UUID 绑定检查   → 是否为当前玩家（可配置关闭）
9. ✅ 黑名单检查      → 是否已使用（双花检测）

任一检查失败 → 跳过该凭证，记录原因，继续下一个
全部通过 → 生成 Package 物品，记录黑名单，移动文件
```

---

*伪代码基于 v0.4 设计计划书，实现时以实际 NeoForge 1.21.1 API 为准。*
