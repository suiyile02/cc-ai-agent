package com.ai.knowledge.dto;

/**
 * 上传响应：{docId, fileName, status}。status: 0-待处理
 */
public record KnowledgeUploadVO(Long docId, String fileName, Integer status) {
}
