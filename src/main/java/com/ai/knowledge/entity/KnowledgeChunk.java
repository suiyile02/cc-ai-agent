package com.ai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库父块(小-大检索): 小块向量进 Qdrant 用于精确召回, 命中小块后按 {@code doc_id + parent_seq}
 * 取本表父块文本喂给模型, 保证"检索粒度(小块)与上下文粒度(父块)分离"。随文档删除/重处理清理。
 */
@Getter
@Setter
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库文档 ID(knowledge_document.id) */
    private Long docId;

    /** 所属文档文件名(冗余, 避免查父块时 join knowledge_document) */
    private String fileName;

    /** 文档内父块序号(从小块 metadata 的 parent_ref 引用) */
    private Integer parentSeq;

    /** 父块文本(结构单元: Markdown 小节/代码函数等) */
    private String content;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
