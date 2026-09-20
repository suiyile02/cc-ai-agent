package com.ai.session.service;

import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.session.entity.ChatSession;
import com.ai.session.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionTitleService} 单元测试：首轮抢占写截断标题的条件性与幂等性、
 * 模型精修结果的清洗、以及"只覆盖自己写过的兜底标题"这一防覆盖规则。
 */
class SessionTitleServiceTest {

    private static final String SESSION_ID = "sid-1";

    private ChatSessionMapper sessionMapper;
    private SessionCacheService sessionCache;
    private AppProperties appProperties;
    private SessionTitleService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        sessionCache = mock(SessionCacheService.class);
        appProperties = new AppProperties();
        // 同线程执行器: 让"异步"精修在测试里确定性跑完, 不必等待线程调度
        service = new SessionTitleService(sessionMapper, sessionCache,
                mock(ChatClientProvider.class), appProperties, directExecutor());
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }

    /** 构造一条标题为 title 的会话 */
    private ChatSession session(String title) {
        ChatSession s = new ChatSession();
        s.setId(1L);
        s.setSessionId(SESSION_ID);
        s.setUserId(9L);
        s.setTitle(title);
        return s;
    }

    /* ---------------- 兜底标题(纯逻辑) ---------------- */

    @Test
    void fallbackCollapsesWhitespaceAndTruncates() {
        // 换行与连续空白折成单个空格(保留英文词间空格, 不把 "annual leave" 变成 "annualleave")
        assertEquals("年假 怎么请", service.fallbackTitleOf("  年假   怎么请\n\n  "));

        String longQuestion = "这是一个非常长的会话标题问题需要被截断掉以适配列表展示的场景一二三四五六七八";
        String fallback = service.fallbackTitleOf(longQuestion);

        assertEquals(25, fallback.length());     // 24 字上限 + 省略号
        assertEquals("…", fallback.substring(fallback.length() - 1));
    }

    @Test
    void fallbackNeverSplitsASurrogatePair() {
        appProperties.getSessionTitle().setFallbackChars(5);

        // 第 5 个 UTF-16 单元是 emoji 的高代理, 必须整体后退一位
        assertEquals("abcd…", service.fallbackTitleOf("abcd😀efgh"));
    }

    @Test
    void fallbackReturnsNullForBlankQuestion() {
        assertNull(service.fallbackTitleOf(null));
        assertNull(service.fallbackTitleOf("   \n\t "));
    }

    /* ---------------- 首轮抢占写库 ---------------- */

    @Test
    void claimsBlankTitleAndEvictsCache() {
        when(sessionMapper.update(any(), any())).thenReturn(1);

        String fallback = service.claimFallback(session(null), "加班调休规定是什么");

        assertEquals("加班调休规定是什么", fallback);
        verify(sessionCache).evict(SESSION_ID);
    }

    @Test
    void skipsClaimWhenSessionAlreadyHasTitle() {
        assertNull(service.claimFallback(session("已有标题"), "新问题"));

        verify(sessionMapper, never()).update(any(), any(Wrapper.class));
        verify(sessionCache, never()).evict(any());
    }

    @Test
    void skipsClaimWhenAnotherRequestWonTheRace() {
        // 条件更新影响 0 行 = 标题已被并发请求写入, 本次不得再触发精修
        when(sessionMapper.update(any(), any())).thenReturn(0);

        assertNull(service.claimFallback(session(""), "同一个问题"));
        verify(sessionCache, never()).evict(any());
    }

    @Test
    void skipsClaimWhenDisabled() {
        appProperties.getSessionTitle().setEnabled(false);

        assertNull(service.claimFallback(session(null), "问题"));
        verify(sessionMapper, never()).update(any(), any(Wrapper.class));
    }

    /* ---------------- 模型精修 ---------------- */

    @Test
    void skipsRefineWhenModelDisabled() {
        appProperties.getSessionTitle().setModelEnabled(false);

        service.refineAsync(SESSION_ID, "问题", "兜底标题");

        verify(sessionMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void keepsFallbackTitleWhenModelUnavailable() {
        ChatClientProvider provider = mock(ChatClientProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        SessionTitleService svc = new SessionTitleService(sessionMapper, sessionCache,
                provider, appProperties, directExecutor());

        svc.refineAsync(SESSION_ID, "问题", "兜底标题");

        verify(sessionMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void persistRefinedOverwritesOnlyTheFallbackItWrote() {
        when(sessionMapper.update(any(), any())).thenReturn(1);

        service.persistRefined(SESSION_ID, "“年假政策。”", "兜底标题");

        ArgumentCaptor<ChatSession> entity = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionMapper).update(entity.capture(), any(Wrapper.class));
        assertEquals("年假政策", entity.getValue().getTitle());
        // SET 子句只能有标题: sessionId 属 WHERE 条件, 写进实体会被当成待更新列
        assertNull(entity.getValue().getSessionId());
        verify(sessionCache).evict(SESSION_ID);
    }

    @Test
    void persistRefinedKeepsTitleWhenFallbackAlreadyReplaced() {
        // 用户已手动改名(标题不再等于兜底值): 影响 0 行, 不得再动缓存
        when(sessionMapper.update(any(), any())).thenReturn(0);

        service.persistRefined(SESSION_ID, "年假政策", "兜底标题");

        verify(sessionCache, never()).evict(any());
    }

    @Test
    void cleanTitleStripsQuotesPrefixPunctuationAndExtraLines() {
        assertEquals("年假政策", service.cleanTitle("“年假政策”。"));
        assertEquals("年假政策", service.cleanTitle("  年假政策\n这是多余的解释"));
        assertEquals("年假政策", service.cleanTitle("标题：年假政策"));
        assertEquals("年假政策", service.cleanTitle("《年假政策》"));
        assertNull(service.cleanTitle("   "));
        assertNull(service.cleanTitle(null));
        assertNull(service.cleanTitle("「」。"));
    }

    @Test
    void cleanTitleCapsOverlongModelOutput() {
        appProperties.getSessionTitle().setMaxChars(10);

        assertEquals(10, service.cleanTitle("这个标题实在是太长了完全超过了配置允许的上限必须被截断").length());
    }
}
