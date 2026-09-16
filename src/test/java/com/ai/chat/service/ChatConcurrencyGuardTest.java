package com.ai.chat.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChatConcurrencyGuard} 单元测试：并发上限、释放后可再获取。
 */
class ChatConcurrencyGuardTest {

    private AppProperties appProperties;
    private ChatConcurrencyGuard guard;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getConcurrency().setMaxConcurrentPerUser(2);
        appProperties.getConcurrency().setAcquireTimeoutMs(100);
        guard = new ChatConcurrencyGuard(appProperties);
    }

    @Test
    void rejectsWhenPermitsExhausted() {
        var h1 = guard.acquire(1L);
        var h2 = guard.acquire(1L);

        BusinessException e = assertThrows(BusinessException.class, () -> guard.acquire(1L));
        assertEquals(ErrorCode.CONCURRENT_LIMIT, e.getErrorCode());

        h1.close();
        h2.close();
    }

    @Test
    void differentUsersHaveIndependentQuotas() {
        var h1 = guard.acquire(1L);
        var h2 = guard.acquire(1L);
        assertNotNull(guard.acquire(2L)); // 另一用户不受影响

        h1.close();
        h2.close();
    }

    @Test
    void releaseAllowsAcquireAgain() {
        var h1 = guard.acquire(1L);
        h1.close();

        assertNotNull(guard.acquire(1L));
    }
}
