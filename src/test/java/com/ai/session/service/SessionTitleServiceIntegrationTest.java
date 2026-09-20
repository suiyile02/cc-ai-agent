package com.ai.session.service;

import com.ai.session.entity.ChatSession;
import com.ai.session.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionTitleService} 真库集成测试：单测把 Mapper 全 mock 掉了，而本功能的
 * 全部正确性都压在两条<b>条件更新</b>的 SQL 上——
 * ①"只填空标题"(抢首轮) ②"只覆盖自己写过的兜底值"(不覆盖人工改名)。
 * 这两条 WHERE 写错不会有任何编译或装配错误，只会静默地覆盖用户数据，故必须打到真实 MySQL。
 *
 * <p>事务随测试回滚，不留脏数据。
 */
@SpringBootTest
@Transactional
class SessionTitleServiceIntegrationTest {

    @Resource
    private SessionTitleService titleService;
    @Resource
    private ChatSessionMapper sessionMapper;

    /** 插入一条无标题会话，返回实体（随测试事务回滚） */
    private ChatSession newUntitledSession() {
        ChatSession s = new ChatSession();
        s.setSessionId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setStatus(1);
        sessionMapper.insert(s);
        return s;
    }

    /** 从库里读回标题（绕开 Redis 缓存与内存实体，只看真实落库结果） */
    private String titleInDb(String sessionId) {
        ChatSession fresh = sessionMapper.selectOne(
                Wrappers.<ChatSession>lambdaQuery().eq(ChatSession::getSessionId, sessionId));
        return fresh == null ? null : fresh.getTitle();
    }

    @Test
    void claimWritesFallbackOnceAndSecondClaimIsBlockedByWhereClause() {
        ChatSession session = newUntitledSession();
        String question = "入职满两年的员工年假有几天，未休完的年假可以折算成工资发放吗，希望得到详细说明？";

        String fallback = titleService.claimFallback(session, question);

        assertEquals(fallback, titleInDb(session.getSessionId()));
        assertTrue(fallback.endsWith("…"), "超长问题应以省略号收尾");

        // 内存实体的 title 仍是旧值(空)，第二次调用必须被 SQL 的 WHERE 挡住而不是再覆盖
        assertNull(titleService.claimFallback(session, "换一个完全不同的问题"));
        assertEquals(fallback, titleInDb(session.getSessionId()));
    }

    @Test
    void persistRefinedReplacesFallbackThenCannotTouchRenamedTitle() {
        ChatSession session = newUntitledSession();
        String fallback = titleService.claimFallback(session, "入职满两年的年假有几天");

        titleService.persistRefined(session.getSessionId(), "「年假政策。」", fallback);
        assertEquals("年假政策", titleInDb(session.getSessionId()));

        // 用户改名后，迟到的精修结果不得覆盖(兜底值已不匹配 → WHERE 命中 0 行)
        sessionMapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getSessionId, session.getSessionId())
                .set(ChatSession::getTitle, "我自己起的名字"));
        titleService.persistRefined(session.getSessionId(), "另一个标题", fallback);

        assertEquals("我自己起的名字", titleInDb(session.getSessionId()));
        assertNotEquals("另一个标题", titleInDb(session.getSessionId()));
    }

    @Test
    void blankQuestionProducesNoTitleAtAll() {
        ChatSession session = newUntitledSession();

        assertNull(titleService.claimFallback(session, "   \n\t "));

        assertNull(titleInDb(session.getSessionId()));
    }
}
