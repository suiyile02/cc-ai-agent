package com.ai.config.props;

import lombok.Data;

/** 文档解析防护({@code app.ingestion.*}): 解析超时与解压炸弹预检(A2)。 */
@Data
public class IngestionProps {

    /** 单文档解析超时毫秒(超时置 status=3) */
    private long parseTimeoutMs = 120000;
    /** docx 解压后内容总大小上限(字节, 默认 512MB, 超限疑似压缩炸弹) */
    private long maxUncompressedBytes = 536870912L;
    /** docx 内部条目数上限 */
    private int maxZipEntries = 5000;
}
