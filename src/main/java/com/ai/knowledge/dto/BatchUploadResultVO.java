package com.ai.knowledge.dto;

/**
 * 批量上传的单个文件结果：逐文件独立成败, 互不影响。
 *
 * @param fileName 原始文件名(为空时显示"未命名文件")
 * @param success  是否上传成功
 * @param docId    成功时的文档 ID(失败为 null)
 * @param status   成功时的状态(0-待处理, 失败为 null)
 * @param message  失败原因(成功为 null)
 */
public record BatchUploadResultVO(String fileName, boolean success,
                                  Long docId, Integer status, String message) {
}
