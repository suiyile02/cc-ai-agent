package com.ai.knowledge.controller;
import com.ai.knowledge.service.KnowledgeDocumentService;

import com.ai.common.DateParamUtils;
import com.ai.common.PageResult;
import com.ai.common.Result;
import com.ai.knowledge.dto.KnowledgeDocumentVO;
import com.ai.knowledge.dto.KnowledgeUploadVO;
import com.ai.user.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库管理接口(需求第 2 章)：上传 / 列表 / 删除 / 重新处理。
 * Controller 只做请求映射与参数绑定，业务逻辑在 {@link KnowledgeDocumentService}。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeDocumentService documentService;

    /**
     * 上传文档(2.1)：校验并保存文件 → 建“待处理”记录 → 异步入库。
     * 上传人取自登录态(UserContext), 无需前端再传 userId。
     *
     * @param file multipart 文件(PDF/DOCX/TXT/MD, ≤50MB)
     * @return 统一响应, data 为上传结果(docId/fileName/status)
     */
    @PostMapping("/upload")
    public Result<KnowledgeUploadVO> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok("文档已提交处理",
                documentService.upload(file, UserContext.requireUserId()));
    }

    /**
     * 批量上传文档(2.1 增强)：一次提交多个文件, 逐文件独立校验与入库,
     * 单个失败不影响其它(响应含每文件成功/失败原因)。
     *
     * @param files 多文件(multipart 字段名 files, 支持多个同名字段)
     * @return 统一响应, data 为逐文件结果列表
     */
    @PostMapping("/upload/batch")
    public Result<List<com.ai.knowledge.dto.BatchUploadResultVO>> uploadBatch(
            @RequestParam("files") MultipartFile[] files) {
        return Result.ok("批量上传完成",
                documentService.uploadBatch(files, UserContext.requireUserId()));
    }

    /**
     * 文档分页列表(2.3)。
     *
     * @param pageNum   页码(从 1 开始)
     * @param pageSize  每页条数
     * @param fileName  文件名模糊过滤(可选)
     * @param status    状态过滤 0/1/2/3(可选)
     * @param startTime 创建时间起(yyyy-MM-dd, 可选)
     * @param endTime   创建时间止(yyyy-MM-dd, 可选)
     * @return 统一响应, data 为分页文档列表
     */
    @GetMapping("/documents")
    public Result<PageResult<KnowledgeDocumentVO>> listDocuments(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<KnowledgeDocumentVO> page = documentService.listDocuments(pageNum, pageSize,
                fileName, status, DateParamUtils.parseDate(startTime),
                DateParamUtils.parseDate(endTime));
        return Result.ok(page);
    }

    /**
     * 删除文档(2.4)：清理向量与关键词索引 → 删除记录 → 删除本地文件。
     *
     * @param id 文档 ID
     * @return 统一响应(无 data)
     */
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return Result.ok("文档已删除");
    }

    /**
     * 文档重新处理(2.5)：清理旧向量/索引并重置状态后重新异步入库。
     *
     * @param id 文档 ID
     * @return 统一响应(无 data)
     */
    @PostMapping("/documents/{id}/reprocess")
    public Result<Void> reprocess(@PathVariable Long id) {
        documentService.reprocess(id);
        return Result.ok("文档已重新提交处理");
    }
}
