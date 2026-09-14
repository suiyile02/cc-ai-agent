package com.ai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库文档(knowledge_document)：上传的原始文档及向量化状态。
 * status: 0-待处理 1-处理中 2-已完成 3-失败
 */
@Getter
@Setter
@TableName("knowledge_document")
public class KnowledgeDocument {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型 PDF/DOCX/TXT/MD */
    private String fileType;

    /** 文件大小(字节) */
    private Long fileSize;

    /** 存储路径(本地文件) */
    private String storagePath;

    /** 所属向量集合 */
    private String collectionName;

    /** 分块数量 */
    private Integer chunkCount = 0;

    /** 处理状态 0/1/2/3 */
    private Integer status = 0;

    /** 失败原因 */
    private String errorMessage;

    /** 上传人 ID */
    private Long createdBy;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间(自动填充) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
