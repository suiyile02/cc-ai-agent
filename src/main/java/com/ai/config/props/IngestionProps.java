package com.ai.config.props;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

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
    /** 切分策略路由配置(按 MIME 路由, YAML 全量外置) */
    private Chunking chunking = new Chunking();

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

    /**
     * 切分策略路由配置({@code app.ingestion.chunking.*})——**刻意外置 YAML**:
     * MIME→策略映射、策略参数、兜底策略全部以部署配置为准, 不在 Java 设默认映射。
     * 与"默认值单一来源=Java"约定的偏离理由: 路由表属运维可调策略, 且由
     * {@code ChunkingConfigValidator} 启动校验兜住引用漂移(引用了不存在的策略名 → 拒绝启动)。
     */
    @Data
    public static class Chunking {

        /** 切分策略路由总开关(关闭时所有文件走 fallback-strategy, 行为与旧固定切分一致) */
        private boolean enabled = true;
        /** 兜底策略名(必配, 启动校验; 路由 miss 时永远落此策略) */
        private String fallbackStrategy;
        /** MIME → 策略名(唯一映射来源, 策略类不再自声明 MIME) */
        private Map<String, String> mimeToStrategy = new HashMap<>();
        /** 策略名 → 参数(每策略一份, 策略按自己 name() 读取) */
        private Map<String, ChunkingStrategyProps> strategies = new HashMap<>();
    }

    /** 单个切分策略的参数(策略只读自己需要的字段) */
    @Data
    public static class ChunkingStrategyProps {
        /** 目标块 token 数 */
        private int chunkSize = 512;
        /** 相邻块重叠 token 数(默认 0=无重叠) */
        private int overlapTokens = 0;
        /** 块最短字符数(过短块丢弃) */
        private int minChunkChars = 100;
        /** 代码类: 视为独立缩进块的最小行首缩进(空格数) */
        private int minIndent = 4;
    }
}
