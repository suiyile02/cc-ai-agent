package com.ai.config.props;

import lombok.Data;

/** 文档解析防护与内容清洗({@code app.ingestion.*}): 解析超时/解压炸弹预检(A2) + 内容清洗(2026-10)。 */
@Data
public class IngestionProps {

    /** 单文档解析超时毫秒(超时置 status=3) */
    private long parseTimeoutMs = 120000;
    /** docx 解压后内容总大小上限(字节, 默认 512MB, 超限疑似压缩炸弹) */
    private long maxUncompressedBytes = 536870912L;
    /** docx 内部条目数上限 */
    private int maxZipEntries = 5000;
    /** 内容清洗配置(Tika 提取后、分块前) */
    private Clean clean = new Clean();

    /**
     * 内容清洗规则(默认值即生效值)。设计原则: 无损项默认开、有误杀风险的项带多重防护
     * 并仅作用于明确安全的类型(页眉页脚统计仅 PDF 且限头尾候选区)。
     */
    @Data
    public static class Clean {

        /** 内容清洗总开关(关闭时行为与清洗功能上线前完全一致) */
        private boolean enabled = true;
        /** U+FFFD(替换符)占全文比例超过该值判定为乱码, 入库失败(status=3) */
        private double replacementMaxDensity = 0.05;
        /** PDF 页眉页脚统计剔除开关(重复行检测, 仅作用于每页头/尾候选区) */
        private boolean pdfHeaderFooter = true;
        /** 页数 < 该值不做页眉页脚统计(样本不足, 结论不可靠) */
        private int headerFooterMinPages = 3;
        /** 候选行出现于 ≥ 该比例的页面即判定为页眉页脚(0~1) */
        private double headerFooterMinFrequency = 0.6;
        /** 每页头/尾各取多少行作为页眉页脚候选 */
        private int headerFooterZoneLines = 3;
        /** 页眉/页脚候选行的最大字符数(超过视为正文, 不参与统计) */
        private int headerFooterMaxLineChars = 100;
    }
}
