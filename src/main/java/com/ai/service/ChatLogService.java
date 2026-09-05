package com.ai.service;

import com.ai.common.PageResult;
import com.ai.dto.ChatLogVO;
import com.ai.entity.ChatLog;
import com.ai.entity.ChatSession;
import com.ai.repository.ChatLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话日志(需求 6.1)：写入与分页查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLogService {

    private final ChatLogRepository chatLogRepository;

    @Transactional
    public void record(ChatSession session, String userMessage, String assistantReply,
            String sourcesJson, String toolCallsJson, String modelName, Integer durationMs) {
        ChatLog logRow = new ChatLog();
        logRow.setSessionId(session.getSessionId());
        logRow.setUserId(session.getUserId());
        logRow.setUserMessage(userMessage);
        logRow.setAssistantReply(assistantReply);
        logRow.setSources(sourcesJson);
        logRow.setToolCalls(toolCallsJson);
        logRow.setModelName(modelName);
        logRow.setDurationMs(durationMs);
        chatLogRepository.save(logRow);
    }

    public PageResult<ChatLogVO> list(int pageNum, int pageSize, String sessionId,
            Long userId, LocalDate startDate, LocalDate endDate) {
        Specification<ChatLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sessionId != null && !sessionId.isBlank()) {
                predicates.add(cb.equal(root.get("sessionId"), sessionId.trim()));
            }
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        startDate.atStartOfDay()));
            }
            if (endDate != null) {
                predicates.add(cb.lessThan(root.get("createdAt"),
                        endDate.plusDays(1).atStartOfDay()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<ChatLog> page = chatLogRepository.findAll(spec,
                PageRequest.of(Math.max(0, pageNum - 1), pageSize,
                        Sort.by(Sort.Direction.DESC, "id")));
        List<ChatLogVO> vos = page.getContent().stream().map(l -> new ChatLogVO(
                l.getId(), l.getSessionId(), l.getUserId(), l.getUserMessage(),
                l.getAssistantReply(), l.getSources(), l.getToolCalls(),
                l.getModelName(), l.getTotalTokens(), l.getDurationMs(),
                l.getCreatedAt())).toList();
        return PageResult.from(page, vos);
    }
}
