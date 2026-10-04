package com.ai.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SensitiveDataMasker} 脱敏规则单元测试(隐私关键):
 * 手机号保留前 3 后 4、邮箱保留首字符与域名、边界(位数不足/超长数字串)不误伤、其余内容原样。
 */
class SensitiveDataMaskerTest {

    @Test
    void phoneIsMaskedKeepingHeadThreeAndTailFour() {
        assertEquals("电话 138****5678 请查收",
                SensitiveDataMasker.mask("电话 13812345678 请查收"));
    }

    @Test
    void emailIsMaskedKeepingFirstCharAndDomain() {
        assertEquals("联系 z***@demo.com",
                SensitiveDataMasker.mask("联系 zhang.san@demo.com"));
        assertEquals("a***@corp.example.com",
                SensitiveDataMasker.mask("a@corp.example.com"), "多级域名整段保留");
    }

    @Test
    void digitBoundariesAreNotOverMasked() {
        // 位数不足 11 位、或前后紧贴其它数字(订单号片段)时不得误伤
        assertEquals("订单号 138123456789 尾部", SensitiveDataMasker.mask("订单号 138123456789 尾部"));
        assertEquals("编号 1333333333 短号", SensitiveDataMasker.mask("编号 1333333333 短号"));
    }

    @Test
    void mixedTextMasksBothAndPreservesRest() {
        String in = "员工 13912345678, 邮箱 li.si@corp.cn, 工单 SO20260101001 已发货";
        String out = SensitiveDataMasker.mask(in);

        assertEquals("员工 139****5678, 邮箱 l***@corp.cn, 工单 SO20260101001 已发货", out);
        assertFalseContains(out, "13912345678");
        assertFalseContains(out, "li.si@");
    }

    @Test
    void nullAndEmptyPassThroughUnchanged() {
        assertNull(SensitiveDataMasker.mask(null));
        assertSame("", SensitiveDataMasker.mask(""), "空串原样返回(同一引用, 零开销路径)");
        assertEquals("无敏感内容", SensitiveDataMasker.mask("无敏感内容"));
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertFalse(haystack.contains(needle), "不应包含原始敏感片段: " + needle);
    }
}
