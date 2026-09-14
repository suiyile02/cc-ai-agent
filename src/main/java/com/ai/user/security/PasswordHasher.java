package com.ai.user.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码哈希工具(PBKDF2-HmacSHA256 + 随机盐)。
 *
 * <p>存储格式：{@code pbkdf2$迭代次数$盐hex$哈希hex}，校验时按相同参数重算并用
 * 常量时间比较，避免引入额外依赖且具备可调计算强度。
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final String PREFIX = "pbkdf2$";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /**
     * 对明文密码做 PBKDF2 加盐哈希。
     *
     * @param rawPassword 明文密码
     * @return 形如 pbkdf2$100000$<盐hex>$<哈希hex> 的存储串
     */
    public static String encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + HexFormat.of().formatHex(salt)
                + "$" + HexFormat.of().formatHex(hash);
    }

    /**
     * 校验明文密码是否匹配存储的哈希串。
     *
     * @param rawPassword   明文密码
     * @param encodedStored 存储串(pbkdf2$迭代$盐$哈希)
     * @return true=匹配
     */
    public static boolean matches(String rawPassword, String encodedStored) {
        if (rawPassword == null || encodedStored == null || !encodedStored.startsWith(PREFIX)) {
            return false;
        }
        try {
            String[] parts = encodedStored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * PBKDF2 派生。
     *
     * @param password   密码字符数组
     * @param salt       盐
     * @param iterations 迭代次数
     * @return 派生密钥字节
     * @throws IllegalStateException 算法不可用(JDK 异常)
     */
    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | java.security.spec.InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }
}
