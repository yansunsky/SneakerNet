package com.yansunsky.sneakernet.crypto;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * HKDF 密钥派生工具（RFC 5869）
 * <p>
 * 基于 HMAC-SHA256 实现 HKDF-Extract + HKDF-Expand 两步派生。
 * 零外部依赖，仅使用 JDK 标准库。
 * </p>
 *
 * <pre>
 * 用法示例:
 *   byte[] prk = HkdfUtil.extract(salt, ikm);
 *   byte[] okm = HkdfUtil.expand(prk, info, 32);
 *   // 或便捷方法:
 *   SecretKey key = HkdfUtil.deriveKey(salt, ikm, info, 32);
 * </pre>
 */
public final class HkdfUtil {

    /** HMAC 算法 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** SHA-256 输出长度（字节） */
    private static final int HASH_LEN = 32;
    /** HKDF-Expand 最大输出长度：255 × HashLen */
    private static final int MAX_OUTPUT_LENGTH = 255 * HASH_LEN;

    private HkdfUtil() {
        // 工具类，禁止实例化
    }

    // ======================== HKDF-Extract ========================

    /**
     * HKDF-Extract 步骤（RFC 5869 §2.2）
     * <p>
     * PRK = HMAC-Hash(salt, IKM)
     * </p>
     *
     * @param salt  盐值；若为 null 或空，则使用 HASH_LEN 长度的零字节
     * @param ikm   输入密钥材料（Input Keying Material）
     * @return      伪随机密钥（PRK），长度 = HASH_LEN
     * @throws GeneralSecurityException 如果 HMAC 初始化失败
     */
    public static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        // RFC 5869: 若 salt 为空，则用 HASH_LEN 个零字节替代
        if (salt == null || salt.length == 0) {
            salt = new byte[HASH_LEN];
        }

        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(salt, HMAC_ALGORITHM));
        return mac.doFinal(ikm);
    }

    // ======================== HKDF-Expand ========================

    /**
     * HKDF-Expand 步骤（RFC 5869 §2.3）
     * <p>
     * 生成指定长度的输出密钥材料（OKM）。
     * 迭代次数 N = ceil(length / HashLen)，最大支持 255 × HashLen 字节。
     * </p>
     *
     * @param prk    伪随机密钥（由 extract 生成），长度至少为 HashLen
     * @param info   上下文/应用特定信息（可为空但不能为 null）
     * @param length 期望输出长度（字节），1 ~ 255 × HashLen
     * @return       输出密钥材料（OKM），长度 = length
     * @throws GeneralSecurityException 如果 HMAC 初始化失败
     * @throws IllegalArgumentException 如果 length 超出范围
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        if (length <= 0) {
            throw new IllegalArgumentException("HKDF-Expand: length 必须为正整数，当前 = " + length);
        }
        if (length > MAX_OUTPUT_LENGTH) {
            throw new IllegalArgumentException(
                    "HKDF-Expand: length 超过最大值 " + MAX_OUTPUT_LENGTH + "，当前 = " + length);
        }

        // 计算迭代次数
        int n = (length + HASH_LEN - 1) / HASH_LEN;

        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));

        byte[] t = new byte[0];        // T(0) = 空字符串
        byte[] okm = new byte[length]; // 最终输出
        int offset = 0;

        for (int i = 1; i <= n; i++) {
            mac.reset();
            // T(i) = HMAC-Hash(PRK, T(i-1) || info || i)
            mac.update(t);
            mac.update(info);
            mac.update((byte) i); // 计数器 1~255
            t = mac.doFinal();

            // 将 T(i) 拷贝到 OKM，最后一轮可能只拷贝部分
            int copyLen = Math.min(HASH_LEN, length - offset);
            System.arraycopy(t, 0, okm, offset, copyLen);
            offset += copyLen;
        }

        return okm;
    }

    // ======================== 便捷方法 ========================

    /**
     * HKDF 便捷方法：Extract + Expand 一步完成
     * <p>
     * 等价于先调用 extract(salt, ikm)，再调用 expand(prk, info, length)。
     * 返回的 SecretKey 算法名为 "AES"，可直接用于 AES 加密。
     * </p>
     *
     * @param salt   盐值（可为 null 或空）
     * @param ikm    输入密钥材料
     * @param info   上下文信息（密钥分离）
     * @param length 输出密钥长度（字节），推荐 16/32（对应 AES-128/AES-256）
     * @return       派生出的 SecretKey（算法 = "AES"）
     * @throws GeneralSecurityException 如果 HKDF 计算失败
     */
    public static SecretKey deriveKey(byte[] salt, byte[] ikm, byte[] info, int length)
            throws GeneralSecurityException {
        byte[] prk = extract(salt, ikm);
        byte[] okm = expand(prk, info, length);
        return new SecretKeySpec(okm, "AES");
    }
}
