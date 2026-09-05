package com.ai.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.PageResult;
import com.ai.dto.SessionVO;
import com.ai.entity.ChatSession;
import com.ai.entity.ChatSession.SessionType;
import com.ai.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话管理(需求第 5 章)：创建 / 列表 / 归档 / 删除(软删并清理记忆)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMemory chatMemory;

    @Transactional
    public SessionVO create(String title, SessionType type, Long userId) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title);
        session.setSessionType(type == null ? SessionType.HYBRID : type);
        session.setStatus(1);
        sessionRepository.save(session);
        log.info("创建会话: sessionId={}, type={}, userId={}",
                session.getSessionId(), session.getSessionType(), userId);
        return toVO(session);
    }

    public PageResult<SessionVO> list(Long userId, int pageNum, int pageSize) {
        Specification<ChatSession> spec = (root, query, cb) ->
                cb.equal(root.get("userId"), userId);
        Page<ChatSession> page = sessionRepository.findAll(spec,
                PageRequest.of(Math.max(0, pageNum - 1), pageSize,
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        List<SessionVO> vos = page.getContent().stream().map(this::toVO).toList();
        return PageResult.from(page, vos);
    }

    /** 归档会话(status=0) */
    @Transactional
    public void archive(Long id, Long userId) {
        ChatSession session = requireOwned(id, userId);
        session.setStatus(0);
        sessionRepository.save(session);
    }

    /** 删除会话：软删(status=0) + 清理对话记忆 */
    @Transactional
    public void delete(Long id, Long userId) {
        ChatSession session = requireOwned(id, userId);
        session.setStatus(0);
        sessionRepository.save(session);
        try {
            chatMemory.clear(session.getSessionId());
            log.info("已清理会话记忆: {}", session.getSessionId());
        } catch (Exception e) {
            log.warn("清理会话记忆失败(忽略): {}", e.getMessage());
        }
    }

    /** 校验会话存在且进行中 */
    @Transactional(readOnly = true)
    public ChatSession requireActive(String sessionId) {
        ChatSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (session.getStatus() == null || session.getStatus() != 1) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已归档");
        }
        return session;
    }

    private ChatSession requireOwned(Long id, Long userId) {
        ChatSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "无权操作他人会话");
        }
        return session;
    }

    private SessionVO toVO(ChatSession s) {
        return new SessionVO(s.getId(), s.getSessionId(), s.getTitle(),
                s.getSessionType(), s.getStatus(), s.getUserId(), s.getCreatedAt());
    }
}
