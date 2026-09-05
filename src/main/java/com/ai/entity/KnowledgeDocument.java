package com.ai.entity;

import com.ai.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库文档(knowledge_document)：上传的原始文档及向量化状态。
 * status: 0-待处理 1-处理中 2-已完成 3-失败
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始文件名 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 文件类型 PDF/DOCX/TXT/MD */
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    /** 文件大小(字节) */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 存储路径(本地文件) */
    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    /** 所属向量集合 */
    @Column(name = "collection_name", nullable = false, length = 100)
    private String collectionName;

    /** 分块数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /** 处理状态 0/1/2/3 */
    @Column(name = "status", nullable = false)
    private Integer status = 0;

    /** 失败原因 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 上传人 ID */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
