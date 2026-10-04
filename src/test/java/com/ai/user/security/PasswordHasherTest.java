package com.ai.user.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PasswordHasher} 单元测试(安全关键, 纯静态零依赖):
 * 存储格式、往返校验、盐唯一性、篡改/畸形存储串拒绝。
 */
class PasswordHasherTest {

    @Test
    void encodeProducesStructuredStorageString() {
        String stored = PasswordHasher.encode("S3cret!密码");

        assertTrue(stored.startsWith("pbkdf2$"), "存储串必须以算法前缀开头");
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length, "格式: pbkdf2$迭代$盐$哈希");
        assertEquals("100000", parts[1], "默认迭代次数");
        assertEquals(32, parts[2].length(), "盐为 16 字节 = 32 个 hex 字符");
        assertEquals(64, parts[3].length(), "哈希为 256 位 = 64 个 hex 字符");
    }

    @Test
    void roundTripMatchesAndRejectsWrongPassword() {
        String stored = PasswordHasher.encode("我的口令Pass123");

        assertTrue(PasswordHasher.matches("我的口令Pass123", stored), "正确口令必须通过");
        assertFalse(PasswordHasher.matches("我的口令Pass124", stored), "错误口令必须拒绝");
        assertFalse(PasswordHasher.matches("", stored));
    }

    @Test
    void samePasswordEncodesWithUniqueSalts() {
        String a = PasswordHasher.encode("same-password");
        String b = PasswordHasher.encode("same-password");

        assertNotEquals(a, b, "随机盐必须保证同一口令两次编码结果不同");
        assertTrue(PasswordHasher.matches("same-password", a));
        assertTrue(PasswordHasher.matches("same-password", b));
    }

    @Test
    void tamperedHashIsRejected() {
        String stored = PasswordHasher.encode("correct-horse");
        String[] parts = stored.split("\\$");
        // 翻转哈希首位
        String flipped = (parts[3].charAt(0) == '0' ? "1" : "0") + parts[3].substring(1);
        String tampered = parts[0] + "$" + parts[1] + "$" + parts[2] + "$" + flipped;

        assertFalse(PasswordHasher.matches("correct-horse", tampered), "哈希被篡改必须拒绝");
    }

    @Test
    void malformedOrForeignStorageStringsAreRejected() {
        assertFalse(PasswordHasher.matches("pw", null));
        assertFalse(PasswordHasher.matches(null, PasswordHasher.encode("pw")));
        assertFalse(PasswordHasher.matches("pw", "{bcrypt}非本算法存储串"), "外来算法存储串直接拒绝");
        assertFalse(PasswordHasher.matches("pw", "pbkdf2$100000$只有盐"), "段数不足直接拒绝");
        assertFalse(PasswordHasher.matches("pw", "pbkdf2$abc$zz$zz"), "非法数字/hex 解析失败按拒绝处理");
    }
}
