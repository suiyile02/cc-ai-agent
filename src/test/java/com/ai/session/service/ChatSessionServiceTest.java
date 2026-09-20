package com.ai.session.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.PageResult;
import com.ai.context.service.ConversationMemoryService;
import com.ai.session.dto.SessionVO;
import com.ai.session.entity.ChatSession;
import com.ai.session.mapper.ChatSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatSessionService#requireActive(String, Long)} 单元测试：
 * 会话归属校验(防止越权在他人会话中对话)与存在性/状态校验。
 */
class ChatSessionServiceTest {

    private ChatSessionMapper sessionMapper;
    private SessionCacheService sessionCache;
    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        sessionCache = mock(SessionCacheService.class);
        service = new ChatSessionService(sessionMapper, sessionCache,
                mock(ChatMemory.class), mock(ConversationMemoryService.class));
    }

    /** 构造一条归属 owner、状态为 status 的会话记录 */
    private ChatSession session(Long owner, int status) {
        ChatSession s = new ChatSession();
        s.setId(1L);
        s.setSessionId("sid-1");
        s.setUserId(owner);
        s.setStatus(status);
        return s;
    }

    @Test
    void ownerCanAccessActiveSession() {
        ChatSession owned = session(9L, 1);
        when(sessionMapper.selectOne(any())).thenReturn(owned);

        ChatSession result = service.requireActive("sid-1", 9L);

        assertSame(owned, result);
    }

    @Test
    void otherUsersSessionIsRejected() {
        when(sessionMapper.selectOne(any())).thenReturn(session(9L, 1));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireActive("sid-1", 8L));
        assertEquals(ErrorCode.AUTH_FAILED, e.getErrorCode());
    }

    @Test
    void missingSessionIsNotFound() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireActive("sid-1", 9L));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void negativeCachedSessionRejectedWithoutDbHit() {
        when(sessionCache.get("sid-1")).thenReturn(null);
        when(sessionCache.isNotFound("sid-1")).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireActive("sid-1", 9L));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, e.getErrorCode());
        verify(sessionMapper, never()).selectOne(any());
    }

    @Test
    void missingSessionWritesNegativeCache() {
        when(sessionCache.get("sid-1")).thenReturn(null);
        when(sessionCache.isNotFound("sid-1")).thenReturn(false);
        when(sessionMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireActive("sid-1", 9L));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, e.getErrorCode());
        verify(sessionCache).putNotFound("sid-1");
    }

    @Test
    void archivedSessionIsNotFound() {
        when(sessionMapper.selectOne(any())).thenReturn(session(9L, 0));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireActive("sid-1", 9L));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void test() {
        PageResult<SessionVO> list = service.list(1L, 1, 5);
        System.out.println(list.records());

    }

    @Test
    void ownerCanRenameAndCacheIsEvicted() {
        ChatSession owned = session(9L, 1);
        when(sessionMapper.selectById(1L)).thenReturn(owned);

        SessionVO vo = service.rename(1L, "年假政策要点", 9L);

        assertEquals("年假政策要点", vo.title());
        verify(sessionMapper).updateById(owned);
        // 会话实体被 Redis 缓存着, 改名后不失效则对话链路仍会读到旧标题
        verify(sessionCache).evict("sid-1");
    }

    @Test
    void renameOnOthersSessionIsRejected() {
        when(sessionMapper.selectById(1L)).thenReturn(session(9L, 1));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.rename(1L, "越权改名", 8L));

        assertEquals(ErrorCode.AUTH_FAILED, e.getErrorCode());
        verify(sessionMapper, never()).updateById(any(ChatSession.class));
    }

    @Test
    void detailReturnsSessionForOwnerAndRejectsOthers() {
        when(sessionMapper.selectById(1L)).thenReturn(session(9L, 1));

        assertEquals("sid-1", service.detail(1L, 9L).sessionId());
        assertThrows(BusinessException.class, () -> service.detail(1L, 8L));
    }
}
