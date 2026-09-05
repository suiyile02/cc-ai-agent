package com.ai.service;

import com.ai.common.PageResult;
import com.ai.dto.ToolCallLogVO;
import com.ai.entity.ToolCallLog;
import com.ai.repository.ToolCallLogRepository;
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
 * 工具调用日志(需求 4.4 / 6.2)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallLogService {

    private final ToolCallLogRepository toolCallLogRepository;

    @Transactional
    public void save(ToolCallLog entry) {
        toolCallLogRepository.save(entry);
        log.info("工具调用日志: {} 耗时 {}ms 状态 {}",
                entry.getToolName(), entry.getDurationMs(), entry.getStatus());
    }

    public PageResult<ToolCallLogVO> list(int pageNum, int pageSize, String sessionId,
            String toolName, String status, LocalDate startDate, LocalDate endDate) {
        Specification<ToolCallLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sessionId != null && !sessionId.isBlank()) {
                predicates.add(cb.equal(root.get("sessionId"), sessionId.trim()));
            }
            if (toolName != null && !toolName.isBlank()) {
                predicates.add(cb.equal(root.get("toolName"), toolName.trim()));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status.trim()));
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
        Page<ToolCallLog> page = toolCallLogRepository.findAll(spec,
                PageRequest.of(Math.max(0, pageNum - 1), pageSize,
                        Sort.by(Sort.Direction.DESC, "id")));
        List<ToolCallLogVO> vos = page.getContent().stream().map(l -> new ToolCallLogVO(
                l.getId(), l.getSessionId(), l.getToolName(), l.getInputParams(),
                l.getOutputResult(), l.getStatus(), l.getDurationMs(),
                l.getErrorMessage(), l.getCreatedAt())).toList();
        return PageResult.from(page, vos);
    }
}
