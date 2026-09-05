package com.ai.controller;

import com.ai.common.PageResult;
import com.ai.common.Result;
import com.ai.dto.KnowledgeDocumentVO;
import com.ai.dto.KnowledgeUploadVO;
import com.ai.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 知识库管理(需求第 2 章)：上传 / 列表 / 删除 / 重新处理。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeDocumentService documentService;

    /** 2.1 文档上传：POST /api/knowledge/upload (multipart, file + userId) */
    @PostMapping("/upload")
    public Result<KnowledgeUploadVO> upload(@RequestParam("file") MultipartFile file,
            @RequestParam("userId") Long userId) {
        return Result.ok("文档已提交处理", documentService.upload(file, userId));
    }

    /** 2.3 文档列表：GET /api/knowledge/documents */
    @GetMapping("/documents")
    public Result<PageResult<KnowledgeDocumentVO>> listDocuments(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<KnowledgeDocumentVO> page = documentService.listDocuments(pageNum, pageSize,
                fileName, status, parseDate(startTime), parseDate(endTime));
        return Result.ok(page);
    }

    /** 2.4 文档删除：DELETE /api/knowledge/documents/{id} */
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return Result.ok("文档已删除");
    }

    /** 2.5 文档重新处理：POST /api/knowledge/documents/{id}/reprocess */
    @PostMapping("/documents/{id}/reprocess")
    public Result<Void> reprocess(@PathVariable Long id) {
        documentService.reprocess(id);
        return Result.ok("文档已重新提交处理");
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            throw new com.ai.common.BusinessException(com.ai.common.ErrorCode.PARAM_ERROR,
                    "日期参数格式应为 yyyy-MM-dd：" + value);
        }
    }
}
