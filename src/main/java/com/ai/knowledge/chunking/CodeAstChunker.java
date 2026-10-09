package com.ai.knowledge.chunking;

import com.ai.common.WarnThrottle;
import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 代码 AST 切分策略(bean {@code code-ast}, tree-sitter): 按**声明边界**(类/函数/方法)切块,
 * 而不是按空行/缩进猜边界——函数体内的空行、多行签名、注解、字符串里的换行都不再干扰切分。
 *
 * <p><b>粒度与父块(小-大检索)</b>: 一个声明 ≤ chunk-size 时整块成块; 超长时拆到其声明类子节点
 * (如类 → 各方法/字段), 子块共享父块 = 外层声明文本(外层 ≤ parent-max-chars 时);
 * 外层过大则子节点各自管理父块。无更细声明的超长节点(大函数)按标点回退再切, 各片共享父块。
 * <b>独立声明不设父块</b>(父块=自身时展开无意义), 故 {@link #everyChunkHasParent()} 为 false。
 *
 * <p><b>语言分派</b>: 按源文件名扩展名(java/py/js/mjs/cjs/ts/tsx/go)选语法包; 语法包 jar 自带
 * 五平台原生库(win/linux/macos × x86_64/aarch64, 实测 Windows/Linux 可用)。
 *
 * <p><b>降级(绝不因语法能力缺失而破坏入库)</b>: 未知扩展名、原生库加载失败(UnsatisfiedLinkError)、
 * 解析异常、或产出为空(纯注释文件) → 整体委托 {@link CodeLineChunker}(与升级前行为一致)。
 * 语言对象缓存复用(不可变), TSParser 每次新建(非线程安全)。
 */
@Slf4j
@Component("code-ast")
public class CodeAstChunker implements ChunkingStrategy {

    /** 扩展名(小写) → 语法包。只覆盖有语法包的语言; 其余(sql/shell 等)由降级通道处理 */
    private static final Map<String, Supplier<TSLanguage>> LANG_BY_EXT = Map.ofEntries(
            Map.entry("java", TreeSitterJava::new),
            Map.entry("py", TreeSitterPython::new),
            Map.entry("js", TreeSitterJavascript::new),
            Map.entry("mjs", TreeSitterJavascript::new),
            Map.entry("cjs", TreeSitterJavascript::new),
            Map.entry("ts", TreeSitterTypescript::new),
            Map.entry("tsx", TreeSitterTypescript::new),
            Map.entry("go", TreeSitterGo::new));

    /** 声明类节点判定: 类型名含以下任一子串(各语法包的命名惯例: class_declaration/function_definition/...) */
    private static final List<String> DECL_KEYS = List.of(
            "declaration", "definition", "function", "method", "class",
            "interface", "enum", "struct", "impl", "trait", "namespace");

    /** 顶层噪声节点(单独成块没有检索价值) */
    private static final Set<String> SKIP_TOP = Set.of("package_clause");

    /** 递归深度防御上限(异常深的表达式树不应造成栈风险) */
    private static final int MAX_DEPTH = 48;

    private final AppProperties appProperties;
    /** 降级通道: 与升级前的 code-line 行为完全一致 */
    private final CodeLineChunker degradeTo;

    /** 语法对象缓存(不可变, 可跨线程复用); 加载失败的扩展名记入 failedExt 不重试刷屏 */
    private final Map<String, TSLanguage> languages = new ConcurrentHashMap<>();
    private final Set<String> failedExt = ConcurrentHashMap.newKeySet();
    private final WarnThrottle degradeWarn = WarnThrottle.of(log);

    public CodeAstChunker(AppProperties appProperties, CodeLineChunker degradeTo) {
        this.appProperties = appProperties;
        this.degradeTo = degradeTo;
    }

    @Override
    public String name() {
        return "code-ast";
    }

    @Override
    public boolean producesParents() {
        return true; // 超长声明拆出的子块共享外层声明做父块(独立声明则无父块)
    }

    @Override
    public boolean everyChunkHasParent() {
        return false; // 独立声明不设父块(父块=自身时展开无意义)
    }

    @Override
    public List<Document> split(Document fullDoc) {
        String text = fullDoc.getText() == null ? "" : fullDoc.getText();
        String ext = extensionOf(fullDoc);
        Supplier<TSLanguage> factory = ext == null ? null : LANG_BY_EXT.get(ext);
        if (factory == null) {
            return degradeTo.split(fullDoc); // 无语法包的语言: 不声张, 与升级前完全一致
        }
        try {
            TSLanguage language = languages.computeIfAbsent(ext, k -> factory.get());
            List<Document> chunks = splitByAst(language, text, fullDoc);
            if (!chunks.isEmpty()) {
                return chunks;
            }
            return degradeTo.split(fullDoc); // 纯注释/空结构: 产出为空也交给降级通道兜底
        } catch (Exception | UnsatisfiedLinkError e) {
            if (failedExt.add(ext)) { // 首次失败告警一次; 原生库缺失等硬故障不刷屏
                degradeWarn.warn("tree-sitter 加载/解析失败, 语言={} 本次起降级 code-line: {}", ext, e.toString());
            }
            return degradeTo.split(fullDoc);
        }
    }

    /**
     * AST 切分: 逐顶层声明发射; 超长声明拆到子声明, 子块共享外层父块(外层够小的话)。
     *
     * @return 分块列表(可能为空: 如纯注释文件)
     */
    private List<Document> splitByAst(TSLanguage language, String text, Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        Ctx ctx = new Ctx(text.getBytes(StandardCharsets.UTF_8),
                Math.max(1, p.getChunkSize()),
                Math.max(Math.max(1, p.getChunkSize()), p.getParentMaxChars()));
        TSParser parser = new TSParser();
        if (!parser.setLanguage(language)) {
            throw new IllegalStateException("setLanguage 拒绝该语法包(ABI 不兼容?)");
        }
        TSTree tree = parser.parseString(null, text);
        TSNode root = tree.getRootNode();
        for (int i = 0; i < root.getNamedChildCount(); i++) {
            TSNode child = root.getNamedChild(i);
            if (isComment(child) || SKIP_TOP.contains(child.getType())) {
                continue;
            }
            emitDecl(child, ctx, 0);
        }
        List<Document> out = new ArrayList<>(ctx.drafts.size());
        for (Draft draft : ctx.drafts) {
            Map<String, Object> md = new HashMap<>(fullDoc.getMetadata());
            if (draft.seq != null) {
                md.put(PARENT_SEQ_KEY, draft.seq); // 有父块的块才带内部键(入库层聚合)
            }
            out.add(Document.builder().text(draft.text).metadata(md).build());
        }
        return out;
    }

    /** 发射一个声明节点: 整块 / 拆子声明 / 标点回退三级, 并决定父块序号 */
    private void emitDecl(TSNode node, Ctx ctx, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        String text = sliceWithLeadingComments(node, ctx.utf8);
        if (text.isBlank()) {
            return;
        }
        if (text.length() <= ctx.chunkSize) {
            ctx.drafts.add(new Draft(text, null)); // 独立声明: 不设父块
            return;
        }
        List<TSNode> kids = declarationChildren(node);
        if (kids.isEmpty()) {
            // 无更细声明(如大函数): 标点回退再切, 各片共享父块(命中一片注入整段)
            int seq = ctx.nextSeq++;
            for (String piece : RecursivePunctuationSplitter.split(text, ctx.chunkSize)) {
                ctx.drafts.add(new Draft(piece, seq));
            }
            return;
        }
        int start = ctx.drafts.size();
        for (TSNode kid : kids) {
            emitDecl(kid, ctx, depth + 1);
        }
        if (ctx.drafts.size() > start && text.length() <= ctx.parentMaxChars) {
            // 子块统一挂外层声明做父块(小-大检索: 命中方法注入整个类)
            int seq = ctx.nextSeq++;
            for (int i = start; i < ctx.drafts.size(); i++) {
                ctx.drafts.get(i).seq = seq;
            }
        }
    }

    /**
     * 声明子节点收集: 优先本层声明类子节点; 本层没有则下钻"包装节点"(如 Java 的 class_body、
     * 各语法的 block/声明列表)直到找到声明, 找不到返回空。
     */
    private List<TSNode> declarationChildren(TSNode node) {
        List<TSNode> direct = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode c = node.getNamedChild(i);
            if (isComment(c)) {
                continue;
            }
            if (isDeclarationLike(c.getType())) {
                direct.add(c);
            }
        }
        if (!direct.isEmpty()) {
            return direct;
        }
        List<TSNode> out = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode c = node.getNamedChild(i);
            if (isComment(c)) {
                continue;
            }
            out.addAll(declarationChildren(c));
        }
        return out;
    }

    /**
     * 声明类节点判定(命名惯例): 类型名含 declaration/definition/function/method/class 等子串的
     * 才可能是切分单元; 但**包装/容器节点必须排除**(class_body/declaration_list/block 等——
     * "class_body" 含 "class" 子串会被误判, 误判会导致父块被内外两层重复覆盖)。
     */
    private boolean isDeclarationLike(String type) {
        if (type.endsWith("_body") || type.endsWith("_list") || type.endsWith("_block")
                || type.equals("body") || type.equals("block")) {
            return false;
        }
        for (String key : DECL_KEYS) {
            if (type.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isComment(TSNode node) {
        return node.getType().endsWith("comment");
    }

    /**
     * 取节点文本(UTF-8 字节切片——tree-sitter 的字节偏移不能直接切 Java 字符串);
     * 紧贴声明上方(无空行)的行/块注释并入本块(文档注释归其描述的对象)。
     */
    private String sliceWithLeadingComments(TSNode node, byte[] utf8) {
        int start = node.getStartByte();
        int topRow = node.getStartPoint().getRow();
        TSNode prev = node.getPrevNamedSibling();
        // 注意: 无前驱时绑定返回的是 isNull() 的包装节点(不是 Java null), 直接 getType() 会抛 TSException
        while (prev != null && !prev.isNull() && isComment(prev)
                && prev.getEndPoint().getRow() + 1 >= topRow) {
            start = prev.getStartByte();
            topRow = prev.getStartPoint().getRow();
            prev = prev.getPrevNamedSibling();
        }
        int end = node.getEndByte();
        if (start < 0 || end > utf8.length || end < start) {
            return "";
        }
        return new String(utf8, start, end - start, StandardCharsets.UTF_8);
    }

    /** 源文件扩展名(小写); 取 metadata.source(Tika 写入的文件名), 缺失返回 null(走降级) */
    private String extensionOf(Document fullDoc) {
        Object src = fullDoc.getMetadata().get("source");
        String name = src == null ? "" : src.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase();
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }

    /** 单次解析的上下文(每调用新建, 无实例共享状态) */
    private static final class Ctx {
        final byte[] utf8;
        final int chunkSize;
        final int parentMaxChars;
        final List<Draft> drafts = new ArrayList<>();
        int nextSeq;

        Ctx(byte[] utf8, int chunkSize, int parentMaxChars) {
            this.utf8 = utf8;
            this.chunkSize = chunkSize;
            this.parentMaxChars = parentMaxChars;
        }
    }

    /** 草稿: 文本 + 父块序号(延迟确定: 外层声明可能给子块统一挂父块) */
    private static final class Draft {
        final String text;
        Integer seq;

        Draft(String text, Integer seq) {
            this.text = text;
            this.seq = seq;
        }
    }
}
