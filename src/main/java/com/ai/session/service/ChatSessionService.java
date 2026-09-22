package com.ai.session.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.PageResult;
import com.ai.context.ConversationMemory;
import com.ai.session.dto.HistoryMessageVO;
import com.ai.session.dto.SessionMessagesVO;
import com.ai.session.dto.SessionVO;
import com.ai.session.entity.ChatSession;
import com.ai.session.SessionType;
import com.ai.session.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话管理业务(需求第 5 章)：创建 / 列表 / 归档 / 删除(软删并清理对话记忆)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper sessionMapper;
    private final SessionCacheService sessionCache;
    private final ChatMemory chatMemory;
    private final ConversationMemory conversationMemoryService;

    /**
     * 创建会话(sessionId=UUID, 即记忆与日志的 conversation_id)。
     *
     * @param title  会话标题(可空)
     * @param type   会话类型 RAG/AGENT/HYBRID(空则默认 HYBRID)
     * @param userId 用户 ID
     * @return 新建会话 VO
     */
    @Transactional
    public SessionVO create(String title, SessionType type, Long userId) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title);
        session.setSessionType(type == null ? SessionType.HYBRID : type);
        session.setStatus(ChatSession.STATUS_ACTIVE);
        sessionMapper.insert(session);
        log.info("创建会话: sessionId={}, type={}, userId={}",
                session.getSessionId(), session.getSessionType(), userId);
        return toVO(session);
    }

    /**
     * 某用户会话分页列表(按创建时间倒序)。
     *
     * @param userId   用户 ID
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 会话分页
     */
    public PageResult<SessionVO> list(Long userId, int pageNum, int pageSize) {
        Page<ChatSession> mpPage = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ChatSession> qw = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getStatus, ChatSession.STATUS_ACTIVE) // 已归档/已删除不出现在列表
                .orderByDesc(ChatSession::getCreatedAt)
                .orderByDesc(ChatSession::getId); // 同秒创建的会话排序稳定(次级排序键)
        sessionMapper.selectPage(mpPage, qw);
        List<SessionVO> vos = mpPage.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(vos, mpPage.getTotal(), (int) mpPage.getCurrent(),
                (int) mpPage.getSize());
    }

    /**
     * 拉取某会话的历史消息(会话回显; 归档会话也允许回看)。
     * 消息来自会话记忆, 仅回显 USER/ASSISTANT 纯文本; 滚动摘要裁剪后
     * 记忆表只保留最近若干条, 更早内容压缩在摘要中一并返回。
     *
     * @param id     会话主键
     * @param userId 当前登录用户(校验归属, 不可读他人会话)
     * @return 消息列表(升序) + 滚动摘要
     */
    @Transactional(readOnly = true)
    public SessionMessagesVO listMessages(Long id, Long userId) {
        ChatSession session = requireOwned(id, userId);
        List<HistoryMessageVO> messages = new ArrayList<>();
        for (Message message : chatMemory.get(session.getSessionId())) {
            String role = switch (message.getMessageType()) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> null; // SYSTEM 等内部消息不回显
            };
            if (role == null || message.getText() == null || message.getText().isBlank()) {
                continue;
            }
            messages.add(new HistoryMessageVO(role, message.getText()));
        }
        return new SessionMessagesVO(messages,
                conversationMemoryService.summaryOf(session.getSessionId()));
    }

    /**
     * 单个会话详情(供前端在自动标题落库后刷新标题, 不必重拉整页列表)。
     *
     * @param id     会话主键
     * @param userId 当前登录用户(校验归属, 不可读他人会话)
     * @return 会话 VO
     * @throws BusinessException 不存在(3001)或无权限(5002)
     */
    @Transactional(readOnly = true)
    public SessionVO detail(Long id, Long userId) {
        return toVO(requireOwned(id, userId));
    }

    /**
     * 归档会话(status=0)。
     *
     * @param id     会话主键
     * @param userId 操作人(校验归属)
     */
    @Transactional
    public void archive(Long id, Long userId) {
        ChatSession session = requireOwned(id, userId);
        session.setStatus(ChatSession.STATUS_CLOSED);
        sessionMapper.updateById(session);
        sessionCache.evict(session.getSessionId());
    }

    /**
     * 手动改名。人工标题优先于自动标题：{@code SessionTitleService} 只在会话首轮触发且
     * 回写时比对"标题仍等于它自己写入的兜底值", 因此改名后不会被自动流程覆盖。
     *
     * @param id     会话主键
     * @param title  新标题(调用方已校验非空且 ≤200)
     * @param userId 操作人(校验归属)
     * @return 改名后的会话 VO(前端就地更新列表项, 无需再拉一次)
     * @throws BusinessException 不存在(3001)或无权限(5002)
     */
    @Transactional
    public SessionVO rename(Long id, String title, Long userId) {
        ChatSession session = requireOwned(id, userId);
        session.setTitle(title);
        sessionMapper.updateById(session);
        sessionCache.evict(session.getSessionId());
        log.info("会话改名: sessionId={}", session.getSessionId());
        return toVO(session);
    }

    /**
     * 删除会话：软删(status=0)并清理该会话记忆。归档与删除同为 status=0，区别只在是否清记忆。
     *
     * @param id     会话主键
     * @param userId 操作人(校验归属)
     */
    @Transactional
    public void delete(Long id, Long userId) {
        ChatSession session = requireOwned(id, userId);
        session.setStatus(ChatSession.STATUS_CLOSED);
        sessionMapper.updateById(session);
        sessionCache.evict(session.getSessionId());
        try {
            chatMemory.clear(session.getSessionId());
            conversationMemoryService.clearSummary(session.getSessionId());
            log.info("已清理会话记忆与摘要: {}", session.getSessionId());
        } catch (Exception e) {
            log.warn("清理会话记忆失败(忽略): {}", e.getMessage());
        }
    }

    /**
     * 获取会话并校验：存在、进行中、且归属当前用户(防止越权操作他人会话)。
     *
     * @param sessionId 会话业务 ID
     * @param userId    当前登录用户 ID
     * @return 会话实体
     * @throws BusinessException 会话不存在(3001)或不属于当前用户(5002)
     */
    @Transactional(readOnly = true)
    public ChatSession requireActive(String sessionId, Long userId) {
        // 先查 Redis 缓存(Redis 异常自动回退 DB), 命中则省一次 MySQL 查询
        ChatSession session = sessionCache.get(sessionId);
        if (session == null && sessionCache.isNotFound(sessionId)) {
            // 负缓存命中(穿透防护): 已确认不存在的会话直接拒绝, 不再打 MySQL
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        if (session == null) {
            session = sessionMapper.selectOne(
                    new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getSessionId, sessionId));
            if (session == null) {
                // 真不存在: 写短 TTL 负缓存, 防同一 sessionId 反复穿透
                sessionCache.putNotFound(sessionId);
                throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
            }
            sessionCache.put(session);
        }
        if (!Integer.valueOf(ChatSession.STATUS_ACTIVE).equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已归档");
        }
        if (userId == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "无权操作他人会话");
        }
        return session;
    }

    /**
     * 获取**进行中**的会话并校验归属当前用户。
     *
     * <p>状态与归属都要查：只查归属会让已归档/软删(status=0)的会话仍能按主键被 {@code detail}
     * 读回（列表已过滤掉它，详情却可枚举），也使"删除后记忆已清空、内容却仍可取"这种不一致长期存在。
     *
     * @param id     会话主键
     * @param userId 用户 ID
     * @return 会话实体
     * @throws BusinessException 不存在或已归档(3001)、无权限(5002)
     */
    private ChatSession requireOwned(Long id, Long userId) {
        ChatSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        if (!Integer.valueOf(ChatSession.STATUS_ACTIVE).equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已归档");
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "无权操作他人会话");
        }
        return session;
    }

    /**
     * 实体 → VO(禁止直接返回实体)。
     *
     * @param s 会话实体
     * @return 会话 VO
     */
    private SessionVO toVO(ChatSession s) {
        return new SessionVO(s.getId(), s.getSessionId(), s.getTitle(),
                s.getSessionType(), s.getStatus(), s.getUserId(), s.getCreatedAt());
    }
}
