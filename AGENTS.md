# Agent Instructions for Spring AI RAG Project (MVC Architecture)

## 项目概览 (Project Overview)

- **架构模式:** 标准 Spring MVC (Model-View-Controller)。
    - **Controller:** 处理 HTTP 请求，参数校验，返回统一 DTO。
    - **Service:** 业务逻辑，事务管理，调用 AI 服务。
    - **Repository:** 数据访问 (MySQL/JPA, Qdrant)。
- **核心栈:** Java 21 (Virtual Threads), Spring Boot 3, Spring AI。
- **数据存储:**
    - **向量库:** Qdrant (RAG 上下文)。
    - **关系库:** MySQL (业务数据)。
    - **缓存:** Redis (会话/热点数据)。

---

## 行为准则 (基于 CLAUDE.md)

### 1. 编码前先思考 (Think Before Coding)

- **RAG 策略:** 在实现检索前，明确 Qdrant 的数据结构假设。
- **消除歧义:** 如果用户要求"更好的搜索"，询问是指语义相似度、混合搜索还是元数据过滤。
- **技术选型:** 如果简单的 SQL 查询能解决问题，请明确反对使用向量搜索（避免过度设计）。

### 2. 极简主义 (Simplicity First)

- **Spring AI:** 优先直接使用 `ChatClient`。除非复用超过 2 次，否则不要创建复杂的包装 Service。
- **拒绝空想:** 不要为了"未来可能的扩展"而添加抽象层。
- **配置:** 保持 `application.yml` 整洁，不要添加未使用的 AI 模型配置。

### 3. 手术式修改 (Surgical Changes)

- **存量代码:** 除非当前任务需要，否则不要重构现有的 Controller/Service 层。
- **清理:** 如果你添加的新 `@Bean` 或 Import 替换了旧的，请立即删除废弃代码。
- **风格:** 严格遵守现有的 Java 代码风格。如果项目使用 Lombok，请坚持使用。

### 4. 目标驱动 (Goal-Driven Execution)

- **RAG 验证:** "实现 RAG"不是目标。"针对查询 X 召回 Top-3 相关文档且分数 > 0.7"才是目标。
- **验证:** 始终建议编写单元测试或 `CommandLineRunner` 代码片段来本地验证 AI 输出。

---

## 技术标准 (Technical Standards)

### Java 21 & Spring MVC 最佳实践

- **虚拟线程:** 在 `application.yml` 中启用虚拟线程 (`spring.threads.virtual.enabled=true`)。
- **模式匹配:** 充分利用 `instanceof` 模式匹配和 Switch 模式匹配。
- **依赖注入:** 优先使用构造器注入 (`@RequiredArgsConstructor`)，禁止字段注入。
- **分层规范:**
    - **Controller:** 仅负责请求映射和参数绑定。禁止包含业务逻辑。
    - **Service:** 包含核心业务逻辑和 `@Transactional`。
    - **Repository:** 继承 `JpaRepository` 或 `CrudRepository`。

---

### 实体类规范 (Entity Conventions)

#### 基础实体

- **包路径:** `com.xxx.entity`，与 `controller`、`service`、`repository` 同级。
- **类定义:**
    - 使用 `@Entity` + `@Table(name = "t_xxx")` 显式指定表名。
    - 表名统一前缀 `t_`，字段名使用 `snake_case`。
    - 主键使用 `@Id` + `@GeneratedValue(strategy = GenerationType.IDENTITY)`。
- **审计字段 (所有实体必须包含):**
    - `createTime` (`@Column(updatable = false)`) — 创建时间。
    - `updateTime` (`@Column`) — 更新时间。
    - 使用 `@PrePersist` / `@PreUpdate` 或 JPA Auditing (`@EntityListeners(AuditingEntityListener.class)`) 自动填充。
- **软删除:** 使用 `@SQLRestriction("deleted = 0")` + `deleted` 字段 (Integer, 0=正常, 1=删除)。
- **关联关系:**
    - 优先使用 `@ManyToOne` + `@OneToMany(mappedBy = "xxx", fetch = FetchType.LAZY)`。
    - 禁止 `FetchType.EAGER`，避免 N+1 和循环加载。
    - 多对多关系优先拆为中间实体，避免直接使用 `@ManyToMany`。
- **枚举字段:** 使用 `@Enumerated(EnumType.STRING)`，禁止使用 `EnumType.ORDINAL`。
- **大文本:** 超过 255 字符的字段使用 `@Column(columnDefinition = "TEXT")`。
- **禁止事项:**
    - Entity 中禁止包含业务方法，Entity 仅作为数据载体。
    - 禁止将 Entity 直接暴露给 Controller 层返回给前端。

#### DTO 与 Entity 转换规范

- **DTO 定义:** 使用 Java `record` 定义，放在 `com.xxx.dto` 包下。
    - 请求 DTO: `XxxRequest` (如 `ChatRequest`, `DocumentUploadRequest`)。
    - 响应 DTO: `XxxResponse` / `XxxVO` (如 `ChatResponse`, `DocumentVO`)。
- **转换方式:**
    - 简单场景: 手动在 Service 层转换。
    - 复杂场景: 使用 MapStruct (`@Mapper`) 自动生成转换代码。
    - 禁止在 Entity 中写 `toDTO()` 方法。

---

### Qdrant 向量存储实体规范

RAG 场景下，Qdrant 的向量数据需要通过实体类进行结构化映射和管理。

#### 1. 文档分块实体 (DocumentChunk)

用于将上传的文档切分为小块，存储在 MySQL 中，同时在 Qdrant 中存储对应的向量引用。

```java
@Entity
@Table(name = "t_document_chunk")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 关联的文档 ID
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    // Qdrant 中的向量 ID（字符串格式，与 Qdrant Point ID 一致）
    @Column(name = "vector_id", nullable = false, length = 64)
    private String vectorId;

    // 文档内容片段
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    // 分块在文档中的起始位置
    @Column(name = "start_position")
    private Integer startPosition;

    // 分块在文档中的结束位置
    @Column(name = "end_position")
    private Integer endPosition;

    // 分块编号（用于有序拼接）
    @Column(name = "chunk_index")
    private Integer chunkIndex;

    // 来源文件名/标题
    @Column(name = "source_name", length = 255)
    private String sourceName;

    // 元数据（JSON 格式，存储额外标签信息）
    @Column(columnDefinition = "JSON")
    private String metadata;

    // 审计字段
    @Column(name = "created_time", updatable = false)
    private LocalDateTime createdTime;

    @Column(name = "updated_time")
    private LocalDateTime updatedTime;

    @Column(name = "deleted")
    private Integer deleted = 0;
}
```

**关键设计要点:**

- `vectorId` 是连接 MySQL 与 Qdrant 的桥梁，必须与 Qdrant Point ID 保持一致。
- `content` 存储实际文本片段，用于检索后的结果展示和调试。
- `metadata` 使用 JSON 类型存储动态标签（如作者、日期、分类），便于 Qdrant 的 Payload 过滤。
- `chunkIndex` 保证分块可按原始顺序重组。

#### 2. 向量检索结果映射 (SearchResult)

Qdrant 检索返回的结果需要封装为统一的结构化对象。

```java
public record SearchResult(
    String pointId,          // Qdrant Point ID
    Float score,             // 相似度分数
    String content,          // 检索到的文本内容（从 MySQL 关联查询）
    Map<String, Object> payload, // Qdrant Payload 元数据
    String sourceName,       // 来源文档名
    Integer chunkIndex       // 分块编号
) {}
```

#### 3. 文档实体 (Document)

管理上传的原始文档及其向量化状态。

```java
@Entity
@Table(name = "t_document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 文档原始文件名
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    // 文档原始内容（全文存储，用于精确匹配）
    @Column(columnDefinition = "TEXT")
    private String content;

    // 向量化状态: PENDING / PROCESSING / COMPLETED / FAILED
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VectorizationStatus status;

    // 向量化失败原因
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // 向量总数
    @Column(name = "vector_count")
    private Integer vectorCount;

    // 使用的 Embedding 模型名称
    @Column(name = "embedding_model", length = 64)
    private String embeddingModel;

    // 分块大小（token 数）
    @Column(name = "chunk_size")
    private Integer chunkSize;

    // 分块重叠大小
    @Column(name = "chunk_overlap")
    private Integer chunkOverlap;

    // 审计字段
    @Column(name = "created_time", updatable = false)
    private LocalDateTime createdTime;

    @Column(name = "updated_time")
    private LocalDateTime updatedTime;

    @Column(name = "deleted")
    private Integer deleted = 0;
}

public enum VectorizationStatus {
    PENDING,      // 待处理
    PROCESSING,   // 处理中
    COMPLETED,    // 已完成
    FAILED        // 失败
}
```

#### 4. Qdrant 配置规范

- **向量维度:** 必须与 Embedding 模型严格匹配。
    - `all-minilm` -> 384 维
    - `text-embedding-3-small` -> 1536 维
    - `bge-large-zh` -> 1024 维
- **距离度量:** 默认使用 `COSINE`，性能场景可切换为 `DOT`。
- **Payload 策略:** Qdrant Payload 只存储 ID 和必要元数据，不存储大文本块。
- **命名规范:**
    - Collection 名称: `col_{业务名}` (如 `col_knowledge`)
    - 不使用中文或特殊字符命名。

#### 5. 向量化流程规范

1. **文档上传** -> 写入 `t_document`，状态设为 `PENDING`。
2. **分块处理** -> 按 `chunkSize` 和 `chunkOverlap` 切分，批量写入 `t_document_chunk`。
3. **向量嵌入** -> 调用 EmbeddingModel 获取向量，批量写入 Qdrant。
4. **状态更新** -> 更新 `t_document` 状态为 `COMPLETED`，记录 `vectorCount`。
5. **失败重试** -> 状态为 `FAILED` 的记录需保留 `errorMessage` 并支持手动重试。

---

### Spring AI & RAG 实现规范

- **提示词 (Prompts):**
    - 将 System Prompts 外部化到 `classpath:/prompts/` (例如 `rag-system-prompt.st`)。
    - 使用 Mustache 或类似模板引擎进行动态上下文注入。
- **检索 (Retrieval):**
    - 在将上下文传给 LLM 之前，**必须**应用 **ReRanking** 步骤或 **Score Threshold** (分数阈值) 过滤。
    - 使用 `VectorStore` 接口进行抽象，但需谨慎配置 Qdrant 的具体参数。
- **对话记忆 (Chat Memory):** 使用 `ChatMemory` 抽象并 backed by Redis。确保 Conversation ID 管理正确。

---

### 统一返回值格式规范 (Unified Response)

所有 Controller 的接口必须返回统一的响应格式，禁止直接返回 Entity 或裸对象。

#### 1. 统一响应封装类 (Result)

```java
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private long timestamp;

    // 成功（无数据）
    public static <T> Result<T> ok() {
        return new Result<>(200, "操作成功", null);
    }

    // 成功（带数据）
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "操作成功", data);
    }

    // 成功（自定义消息）
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    // 失败（业务异常）
    public static <T> Result<T> fail(String message) {
        return new Result<>(400, message, null);
    }

    // 失败（业务异常 + 自定义码）
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    // 失败（从异常提取）
    public static <T> Result<T> fail(Exception e) {
        return new Result<>(500, e.getMessage(), null);
    }

    // 分页数据封装
    public static <T> Result<PageResult<T>> okPage(PageResult<T> page) {
        return new Result<>(200, "查询成功", page);
    }

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // getters...
}
```

#### 2. 分页结果封装 (PageResult)

```java
public class PageResult<T> {

    private List<T> records;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setTotalPages((int) Math.ceil((double) total / size));
        return result;
    }
}
```

#### 3. 业务异常枚举 (ErrorCode)

```java
public enum ErrorCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // 业务异常码（40000~49999）
    DOC_NOT_FOUND(40001, "文档不存在"),
    DOC_UPLOAD_FAILED(40002, "文档上传失败"),
    VECTORIZATION_FAILED(40003, "向量化处理失败"),
    CHUNK_SIZE_INVALID(40004, "分块大小设置无效"),
    RAG_RETRIEVAL_FAILED(40005, "RAG 检索失败"),
    EMBEDDING_MODEL_NOT_CONFIGURED(40006, "Embedding 模型未配置"),
    QDRANT_CONNECTION_FAILED(40007, "Qdrant 连接失败"),
    INVALID_VECTOR_DIMENSION(40008, "向量维度与模型不匹配");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
```

#### 4. 全局异常处理 (GlobalExceptionHandler)

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    // 参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        return Result.fail(400, message);
    }

    // 未捕获异常
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 生产环境建议记录日志，不将异常详情返回给前端
        log.error("未捕获异常", e);
        return Result.fail(500, "服务器内部错误，请稍后重试");
    }
}
```

#### 5. 统一响应使用规范

- **Controller 层:** 所有 `@RequestMapping` 方法返回 `Result<T>` 或 `Result<PageResult<T>>`。
- **成功响应示例:**

    ```json
    {
        "code": 200,
        "message": "操作成功",
        "data": { ... },
        "timestamp": 1725484800000
    }
    ```

- **失败响应示例:**

    ```json
    {
        "code": 40001,
        "message": "文档不存在",
        "data": null,
        "timestamp": 1725484800000
    }
    ```

- **禁止事项:**
    - 禁止在 Controller 中直接 `return new ResponseEntity<>(...)`。
    - 禁止将 `Result` 再包裹一层 `List` 或 `Map`。
    - 禁止在 `data` 字段中返回 Entity 对象，必须使用 DTO/VO。
    - 禁止在异常处理中返回堆栈信息给前端。

---

## 测试策略 (Testing Strategy)

- **单元测试:** 使用 Mockito Mock `ChatModel` 和 `EmbeddingModel`。单元测试中禁止调用真实 API。
- **集成测试:** 尽可能使用 `@SpringBootTest` 配合 Testcontainers 启动 MySQL 和 Qdrant。
- **断言:** 不仅要验证文本回复，还要验证注入到 Prompt 中的 **Context 内容**是否正确。

---

## 常用命令 (Common Commands)

- **开发:** `./mvnw spring-boot:run`
- **测试:** `./mvnw test`
- **清理:** `./mvnw clean`
- **基础设施:** `docker-compose up -d` (启动 Qdrant, MySQL, Redis)
