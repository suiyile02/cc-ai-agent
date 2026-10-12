package com.ai.knowledge.chunking;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 结构感知切分策略: 只在**文档内最浅标题层级**(如 #/##)断节, 次级标题连同正文并入所在大节
 * ——杜绝"零正文光杆标题块"(旧规则让每个标题独立成节, 短标题块既占召回名额又喂不出内容,
 * BM25 长度归一还使其分数虚高)。每个小块携带所属节序号
 * ({@link ChunkingStrategy#PARENT_SEQ_KEY}), 入库层据此把同节小块拼成父块(小-大检索);
 * 大节超长时用 {@link RecursivePunctuationSplitter} 标点回退再切。
 *
 * <p><b>封面单标题(2026-10-13)</b>: "封面 # + 多个 ## 章"的文档改用 ## 分章, 封面行并入第一章
 * ——否则整篇挤成单一超大父块, 真实体量手册只能靠窗口降级。详见 {@link #coverAwareLevel}。
 *
 * <p>无标题文本退化为整篇单节(全部小块同父块); 不丢内容(拼接各块可还原全部文本)。
 * 标题并入所属小节的首块。无共享实例状态, 线程安全。
 */
@Component("md-structure")
@RequiredArgsConstructor
public class MdStructureChunker implements ChunkingStrategy {

    private final AppProperties appProperties;

    @Override
    public String name() {
        return "md-structure";
    }

    @Override
    public boolean producesParents() {
        return true; // Markdown 节是天然父块(小-大检索)
    }

    @Override
    public List<Document> split(Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        String text = fullDoc.getText() == null ? "" : fullDoc.getText();
        List<String> sections = splitByHeadings(text);
        List<Document> out = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            // 节内超长 → 标点回退再切; 未超长则整节一块(含标题)
            List<String> pieces = RecursivePunctuationSplitter.split(sections.get(s), p.getChunkSize());
            for (String piece : pieces) {
                Map<String, Object> md = new HashMap<>(fullDoc.getMetadata());
                md.put(PARENT_SEQ_KEY, s); // 小节序号 = 父块序号(入库层聚合)
                out.add(Document.builder()
                        .text(piece)
                        .metadata(md)
                        .build());
            }
        }
        return out;
    }

    /**
     * 按**最浅标题层级**切节: 先扫描全文取断节层级(见 {@link #shallowestHeadingDepth}),
     * 再只在同深度标题处断节。次级标题行保留在节内(语境不丢)。
     *
     * <p>首个断节标题处**不收尾**: 其前的封面标题/无标题引言并入本节点——否则封面会独立成
     * "零正文标题块"(既占召回名额又喂不出内容, 正是本策略要消灭的形态)。
     *
     * @param text 原文
     * @return 小节列表(按出现顺序, 内容不丢)
     */
    private List<String> splitByHeadings(String text) {
        List<String> sections = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sections;
        }
        String[] lines = text.split("\n", -1);
        int topLevel = shallowestHeadingDepth(lines);
        if (topLevel < 0) {
            sections.add(text.trim());
            return sections;
        }
        StringBuilder current = new StringBuilder();
        boolean seenTop = false; // 是否已遇到首个断节标题(其前内容并入首节, 不收尾)
        for (String line : lines) {
            if (headingDepth(line) == topLevel) {
                if (seenTop) {
                    flush(sections, current);
                    current = new StringBuilder();
                }
                seenTop = true;
            }
            current.append(line).append('\n');
        }
        flush(sections, current);
        return sections;
    }

    /** 收尾当前节(去空白后非空才入, 空节丢弃)。 */
    private static void flush(List<String> sections, StringBuilder current) {
        if (current.length() > 0 && !current.toString().trim().isEmpty()) {
            sections.add(current.toString().trim());
        }
    }

    /**
     * 断节层级判定：取全文最浅标题深度，但有三条例外。
     *
     * <ol>
     *   <li>首标题前存在无标题引言 → 强制以 # 为界（封面标题作顶级、引言并入其节）；</li>
     *   <li><b>封面单标题（2026-10-13）</b>：最浅标题唯一且位于首个非空白行（文档大标题/封面），
     *       且其下一层标题出现 ≥2 次（确有多个同级章节）→ 改用下一层断节。否则
     *       "封面 # + 多个 ## 章"的文档会整篇挤成单一超大父块，真实体量下只能走窗口降级；</li>
     *   <li>无任何标题 → -1（调用方退化为整篇单节）。</li>
     * </ol>
     *
     * @param lines 全文按行切分
     * @return 断节层级(1~4)；无标题返回 -1
     */
    private static int shallowestHeadingDepth(String[] lines) {
        int min = -1;
        boolean preamble = false;
        int firstHeadingLine = -1;
        for (int i = 0; i < lines.length; i++) {
            int depth = headingDepth(lines[i]);
            if (depth < 0) {
                continue;
            }
            if (min < 0) {
                // 第一个标题之前已有实质文本 → 引言段场景(封面标题作顶级, 引言并入其节)
                preamble = i > indexOfFirstNonBlank(lines);
                min = depth;
                firstHeadingLine = i;
            } else {
                min = Math.min(min, depth);
            }
        }
        if (min < 0) {
            return -1;
        }
        if (preamble) {
            return 1;
        }
        return coverAwareLevel(lines, min, firstHeadingLine);
    }

    /**
     * 封面单标题识别：最浅标题唯一且就在首个非空白行 → 忽略它取下一层，但要求该下一层
     * 出现 ≥2 次（否则"只有一个次级标题"的文档按原层级切即可，避免无谓分裂）。
     *
     * @param lines            全文按行切分
     * @param min              已求得的最浅层级
     * @param firstHeadingLine 首个标题所在行号
     * @return 断节层级（识别出封面则为其下一层，否则维持 {@code min}）
     */
    private static int coverAwareLevel(String[] lines, int min, int firstHeadingLine) {
        if (firstHeadingLine != indexOfFirstNonBlank(lines)) {
            return min; // 最浅标题不在文档开头: 不是封面, 维持原层级
        }
        int countMin = 0;
        int next = -1;
        for (String line : lines) {
            int depth = headingDepth(line);
            if (depth == min) {
                countMin++;
            } else if (depth > min && (next < 0 || depth < next)) {
                next = depth;
            }
        }
        if (countMin != 1 || next < 0) {
            return min; // 最浅层不止一个 或 无更深层: 不是"封面 + 章"结构
        }
        int countNext = 0;
        for (String line : lines) {
            if (headingDepth(line) == next) {
                countNext++;
            }
        }
        return countNext >= 2 ? next : min;
    }

    /** 判定标题行并返回层级(1~4); 非标题返回 -1。规则: 行首 1~4 个 # 后跟空格。 */
    private static int headingDepth(String line) {
        if (line == null || line.isBlank()) {
            return -1;
        }
        int hash = 0;
        while (hash < line.length() && hash < 4 && line.charAt(hash) == '#') {
            hash++;
        }
        return hash >= 1 && hash <= 4 && hash < line.length() && line.charAt(hash) == ' ' ? hash : -1;
    }

    /** 首个非空白行的下标(全空返回 0)。 */
    private static int indexOfFirstNonBlank(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return 0;
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
