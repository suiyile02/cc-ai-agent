package com.ai.dto;

import java.time.LocalDateTime;

/**
 * 知识库文档列表项 VO。
 */
public record KnowledgeDocumentVO(
        Long id,
        String fileName,
        String fileType,
        Long fileSize,
        Integer chunkCount,
        Integer status,
        String errorMessage,
        Long createdBy,
        LocalDateTime createTime) {
}
