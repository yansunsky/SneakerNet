package com.yansunsky.sneakernet.crypto;

import com.yansunsky.sneakernet.data.Voucher;
import com.yansunsky.sneakernet.data.VoucherBlacklist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 加密核心 — ECDH + ECDSA + AES-256-GCM + HKDF
 * <p>
 * 安全模型：
 * <ul>
 *   <li>导出时：生成临时 ECC 密钥对 → ECDH 协商 → HKDF 派生 → AES-GCM 加密 → ECDSA 签名</li>
 *   <li>导入时：ECDSA 验签 → ECDH 协商 → HKDF 派生 → AES-GCM 解密 → 安全策略检查</li>
 * </ul>
 * <p>
 * 前向安全：每次导出使用临时密钥对，历史凭据泄露不影响未来。
 * 非否认性：ECDSA 签名确保签发者无法否认。
 */
public final class VoucherCrypto {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoucherCrypto.class);

    // ─── 常量 ───
    private static final int GCM_IV_LENGTH = 12;       // AES-GCM IV 长度（字节）
    private static final int GCM_TAG_LENGTH = 128;     // AES-GCM 认证标签位数
    private static final byte[] HKDF_INFO = "SneakerNet_v2_Encryption".getBytes(StandardCharsets.UTF_8);

    private VoucherCrypto() {
    } // 工具类，不实例化

    // ─── 导出加密（服务器 A 调用）───

    /**
     * 加密并签名容器数据，生成 Voucher
     * <p>
     * 步骤：
     * 1. 生成临时 ECC 密钥对（前向安全）
     * 2. ECDH 密钥协商（临时私钥 + 目标服务器公钥）
     * 3. HKDF 密钥派生
     * 4. AES-256-GCM 加密
     * 5. 计算 itemHash（防内容替换）
     * 6. ECDSA 签名（用本服长期私钥）
     * 7. 组装 Voucher
     *
     * @param localKeyPair 本服密钥对（用于 ECDSA 签名）
     * @param targetPubKey 目标服务器公钥（用于 ECDH 协商）
     * @param plaintextNbt 容器 NBT 二进制数据
     * @param playerUuid   导出玩家 UUID
     * @param ttlSeconds   凭据有效期（秒）
     * @return 加密后的 Voucher
     */
    public static Voucher encryptAndSign(
            KeyPair localKeyPair,
            PublicKey targetPubKey,
            byte[] plaintextNbt,
            UUID playerUuid,
            int ttlSeconds
    ) throws GeneralSecurityException {
        // [1] 生成临时 ECC 密钥对（前向安全）
        KeyPair ephemeralKeyPair = generateEphemeralKeyPair();

        // [2] ECDH 密钥协商
        byte[] sharedSecret = ecdhKeyAgreement(
                ephemeralKeyPair.getPrivate(), // A 的临时私钥
                targetPubKey                    // B 的公钥
        );

        // [3] HKDF 密钥派生
        byte[] salt = ephemeralKeyPair.getPublic().getEncoded(); // 临时公钥作为 salt
        SecretKey aesKey = HkdfUtil.deriveKey(salt, sharedSecret, HKDF_INFO, 32);

        // [4] AES-256-GCM 加密
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        byte[] encryptedData = aesGcmEncrypt(aesKey, iv, plaintextNbt);

        // [5] 计算物品哈希（防内容替换）
        byte[] itemHash = MessageDigest.getInstance("SHA-256").digest(plaintextNbt);
        byte[] itemHashTruncated = Arrays.copyOfRange(itemHash, 0, 8); // 前8字节

        // [6] 组装签名数据
        long timestamp = Instant.now().getEpochSecond();
        byte[] signData = concat(
                ephemeralKeyPair.getPublic().getEncoded(),
                encryptedData,
                iv,
                playerUuid.toString().getBytes(StandardCharsets.UTF_8),
                longToBytes(timestamp),
                itemHashTruncated
        );

        // [7] ECDSA 签名（用 A 的长期私钥）
        byte[] signature = ecdsaSign(localKeyPair.getPrivate(), sha256(signData));

        // [8] 组装 Voucher
        String issuerKeyId = KeyManager.computeKeyId(localKeyPair.getPublic());

        return new Voucher(
                2, // version
                issuerKeyId,
                Base64.getEncoder().encodeToString(ephemeralKeyPair.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(encryptedData),
                Base64.getEncoder().encodeToString(iv),
                playerUuid,
                timestamp,
                HexFormat.of().formatHex(itemHashTruncated),
                ttlSeconds,
                Base64.getEncoder().encodeToString(signature)
        );
    }

    // ─── 导入解密（服务器 B 调用）───

    /**
     * 解密结果
     */
    public record DecryptResult(
            boolean success,
            byte[] plaintextNbt,
            Voucher voucher,
            String failureReason
    ) {
        public static DecryptResult success(byte[] nbt, Voucher v) {
            return new DecryptResult(true, nbt, v, null);
        }

        public static DecryptResult fail(String reason) {
            return new DecryptResult(false, null, null, reason);
        }
    }

    /**
     * 验证并解密 Voucher
     * <p>
     * 验证顺序（尽早拒绝无效凭证）：
     * 1. 签发者查找（issuerKeyId 在可信列表中？）
     * 2. ECDSA 签名验证（数据完整性）
     * 3. ECDH + AES-GCM 解密
     * 4. itemHash 校验（明文完整性）
     * 5. TTL 过期检查
     * 6. UUID 绑定检查
     * 7. 黑名单检查（防双花）
     *
     * @param localKeyPair     本服密钥对（用于 ECDH 协商）
     * @param voucher          待验证的凭据
     * @param keyManager       密钥管理器（查找签发者公钥）
     * @param currentPlayerUuid 当前导入的玩家 UUID
     * @param blacklist        黑名单管理器
     * @param requirePlayerMatch 是否要求玩家 UUID 匹配
     */
    public static DecryptResult decryptAndVerify(
            KeyPair localKeyPair,
            Voucher voucher,
            KeyManager keyManager,
            UUID currentPlayerUuid,
            VoucherBlacklist blacklist,
            boolean requirePlayerMatch
    ) {
        try {
            // [1] 查找签发者公钥
            PublicKey issuerPubKey = keyManager.getTrustedPublicKey(voucher.issuerKeyId());
            if (issuerPubKey == null) {
                return DecryptResult.fail("未知签发者: " + voucher.issuerKeyId());
            }

            // [2] 重建签名数据并验证 ECDSA 签名
            PublicKey ephemeralPubKey = KeyManager.decodePublicKeyFromBase64(voucher.ephemeralPublicKey());
            byte[] encryptedData = Base64.getDecoder().decode(voucher.encryptedData());
            byte[] iv = Base64.getDecoder().decode(voucher.iv());

            byte[] signData = concat(
                    ephemeralPubKey.getEncoded(),
                    encryptedData,
                    iv,
                    voucher.playerUuid().toString().getBytes(StandardCharsets.UTF_8),
                    longToBytes(voucher.timestamp()),
                    HexFormat.of().parseHex(voucher.itemHash())
            );

            if (!ecdsaVerify(issuerPubKey, sha256(signData),
                    Base64.getDecoder().decode(voucher.signature()))) {
                return DecryptResult.fail("签名验证失败（数据可能被篡改）");
            }

            // [3] ECDH 密钥协商（B 侧）
            byte[] sharedSecret = ecdhKeyAgreement(
                    localKeyPair.getPrivate(),  // B 的长期私钥
                    ephemeralPubKey             // A 的临时公钥
            );

            // [4] HKDF 密钥派生（参数与加密时完全相同）
            byte[] salt = ephemeralPubKey.getEncoded();
            SecretKey aesKey = HkdfUtil.deriveKey(salt, sharedSecret, HKDF_INFO, 32);

            // [5] AES-256-GCM 解密
            byte[] plaintextNbt;
            try {
                plaintextNbt = aesGcmDecrypt(aesKey, iv, encryptedData);
            } catch (BadPaddingException e) {
                return DecryptResult.fail("解密失败（密钥错误或数据损坏）");
            }

            // [6] 验证 itemHash
            byte[] computedHash = MessageDigest.getInstance("SHA-256").digest(plaintextNbt);
            String computedHashHex = HexFormat.of().formatHex(computedHash, 0, 8);
            if (!computedHashHex.equals(voucher.itemHash())) {
                return DecryptResult.fail("数据完整性校验失败");
            }

            // [7] TTL 过期检查
            long elapsed = Instant.now().getEpochSecond() - voucher.timestamp();
            if (elapsed > voucher.ttl()) {
                return DecryptResult.fail("凭据已过期（TTL: " + voucher.ttl() + "秒，已过: " + elapsed + "秒）");
            }

            // [8] 玩家 UUID 绑定检查
            if (requirePlayerMatch && !voucher.playerUuid().equals(currentPlayerUuid)) {
                return DecryptResult.fail("凭据与当前玩家不匹配");
            }

            // [9] 一次性检查（黑名单）
            String voucherId = computeVoucherId(voucher);
            if (blacklist.isRedeemed(voucherId)) {
                return DecryptResult.fail("凭据已使用（双花检测）");
            }

            // [10] 记录到黑名单（防双花）
            blacklist.redeem(voucherId, voucher.issuerKeyId(), voucher.playerUuid(), voucher.ttl());

            return DecryptResult.success(plaintextNbt, voucher);

        } catch (GeneralSecurityException e) {
            LOGGER.error("[SneakerNet] 解密验证异常", e);
            return DecryptResult.fail("解密验证失败: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 解密验证异常", e);
            return DecryptResult.fail("解密验证失败: " + e.getMessage());
        }
    }

    // ─── 底层加密操作 ───

    /**
     * 生成临时 ECC 密钥对（每次导出都不同 → 前向安全）
     */
    static KeyPair generateEphemeralKeyPair() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), SecureRandom.getInstanceStrong());
        return gen.generateKeyPair();
    }

    /**
     * ECDH 密钥协商
     */
    static byte[] ecdhKeyAgreement(PrivateKey myPrivate, PublicKey theirPublic) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(myPrivate);
        ka.doPhase(theirPublic, true);
        return ka.generateSecret();
    }

    /**
     * ECDSA 签名
     */
    static byte[] ecdsaSign(PrivateKey privateKey, byte[] data) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey, SecureRandom.getInstanceStrong());
        sig.update(data);
        return sig.sign();
    }

    /**
     * ECDSA 验签
     */
    static boolean ecdsaVerify(PublicKey publicKey, byte[] data, byte[] signature) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }

    /**
     * AES-256-GCM 加密
     */
    static byte[] aesGcmEncrypt(SecretKey key, byte[] iv, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(plaintext);
    }

    /**
     * AES-256-GCM 解密
     */
    static byte[] aesGcmDecrypt(SecretKey key, byte[] iv, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(ciphertext);
    }

    /**
     * 计算 Voucher ID（用于黑名单）
     * <p>
     * voucherId = SHA256(ephemeralPublicKey + timestamp) 的 Hex 字符串
     */
    public static String computeVoucherId(Voucher voucher) {
        try {
            byte[] data = concat(
                    Base64.getDecoder().decode(voucher.ephemeralPublicKey()),
                    longToBytes(voucher.timestamp())
            );
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    // ─── 工具方法 ───

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    private static byte[] concat(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] arr : arrays) totalLen += arr.length;
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }

    private static byte[] longToBytes(long value) {
        return new byte[]{
                (byte) (value >> 56), (byte) (value >> 48),
                (byte) (value >> 40), (byte) (value >> 32),
                (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value
        };
    }
}
