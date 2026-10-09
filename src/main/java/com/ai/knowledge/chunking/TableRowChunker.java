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
 * 表格类切分策略(bean {@code table-row}): 一行一"小块"(检索单元), 连续若干行共用一个父块
 * ({@link ChunkingStrategy#PARENT_SEQ_KEY}, 小-大检索的上下文单元)。
 *
 * <p><b>为什么单独成策略</b>: 表格按通用文本切会切断行与列的对应关系, 且裸行脱离表头后语义为零
 * ——"经理,500元,高铁二等座"既匹配不到问题里的"住宿标准"(嵌入/BM25 都丢词), 也无法安全引用。
 * 本策略把列名内嵌进每一行(`列名=值`), 表头不单独成块; 行号保留在文本里便于回查原表。
 *
 * <p><b>两种输入形状</b>(均来自 Tika, 已实测):
 * <ol>
 *   <li>CSV/TSV 原样文本: 分隔符嗅探(, \t ; |) + RFC4180 引号解析(引号内分隔符/换行不算边界,
 *       {@code ""} 转义一个引号)——裸 {@code split(",")} 会把带逗号字段切碎;</li>
 *   <li>Excel(xls/xlsx)Tika 文本: sheet 名单占一行, 数据行以 {@code \t} 开头、单元格以 {@code \t} 分隔;
 *       每个 sheet 首行(可配)为表头, sheet 名作为行文本前缀(多 sheet 消歧)。</li>
 * </ol>
 * 无实例状态, 线程安全。空单元格跳过不渲染; 超长行(如描述列很大)按标点回退再切, 不丢内容。
 */
@Component("table-row")
@RequiredArgsConstructor
public class TableRowChunker implements ChunkingStrategy {

    private final AppProperties appProperties;

    @Override
    public String name() {
        return "table-row";
    }

    @Override
    public boolean producesParents() {
        return true; // 同组若干数据行是天然父块(小-大检索)
    }

    @Override
    public List<Document> split(Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        String text = fullDoc.getText() == null ? "" : fullDoc.getText();
        int rowsPerParent = Math.max(1, p.getRowsPerParent());
        List<Table> tables = parse(text, titleOf(fullDoc), p.isHasHeader());
        List<Document> out = new ArrayList<>();
        int parentSeq = 0;
        for (Table table : tables) {
            List<Row> rows = table.rows();
            for (int start = 0; start < rows.size(); start += rowsPerParent) {
                int end = Math.min(rows.size(), start + rowsPerParent);
                for (int i = start; i < end; i++) {
                    String rendered = render(table, rows.get(i));
                    if (rendered.isBlank()) {
                        continue;
                    }
                    for (String piece : RecursivePunctuationSplitter.split(rendered, p.getChunkSize())) {
                        Map<String, Object> md = new HashMap<>(fullDoc.getMetadata());
                        md.put(PARENT_SEQ_KEY, parentSeq); // 行组序号 = 父块序号(入库层聚合)
                        out.add(Document.builder()
                                .text(piece)
                                .metadata(md)
                                .build());
                    }
                }
                parentSeq++;
            }
        }
        return out;
    }

    /** 一张表: 标题(CSV=文件名主体 / Excel=sheet 名, 可空) + 列名(表头, 可空) + 数据行 */
    private record Table(String title, List<String> columns, List<Row> rows) {
    }

    /** 数据行: 原始行号(1 基, 回查原表用) + 单元格 */
    private record Row(int lineNo, List<String> cells) {
    }

    /** 解析为表(Excel 多 sheet 即多表): 按"是否存在 {@code \t} 开头的行"判别输入形状 */
    private List<Table> parse(String text, String fallbackTitle, boolean hasHeader) {
        List<Table> tables = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tables;
        }
        String[] lines = text.split("\n", -1);
        boolean spreadsheet = false;
        for (String line : lines) {
            if (line.startsWith("\t")) {
                spreadsheet = true;
                break;
            }
        }
        if (spreadsheet) {
            parseSpreadsheet(lines, hasHeader, tables);
        } else {
            parseDelimited(text, fallbackTitle, hasHeader, tables);
        }
        return tables;
    }

    /** Excel 形状: 非 {@code \t} 开头行 = sheet 名(遇新名收尾上一表); {@code \t} 开头行 = 数据行 */
    private void parseSpreadsheet(String[] lines, boolean hasHeader, List<Table> tables) {
        String title = null;
        List<String> columns = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue; // 空行是 sheet 间分隔, 不产生内容
            }
            if (!line.startsWith("\t")) {
                if (!rows.isEmpty() || !columns.isEmpty()) {
                    tables.add(new Table(title, columns, rows));
                    columns = new ArrayList<>();
                    rows = new ArrayList<>();
                }
                title = line.trim();
                continue;
            }
            List<String> cells = splitCells(line.substring(1), '\t');
            if (hasHeader && columns.isEmpty()) {
                columns = cells; // 每 sheet 首行(可配)为表头
            } else {
                rows.add(new Row(i + 1, cells));
            }
        }
        if (!rows.isEmpty() || !columns.isEmpty()) {
            tables.add(new Table(title, columns, rows));
        }
    }

    /** CSV/TSV 形状: 单表; 分隔符嗅探 + RFC4180 记录解析 */
    private void parseDelimited(String text, String fallbackTitle, boolean hasHeader, List<Table> tables) {
        char delim = sniffDelimiter(text);
        List<Row> records = parseDelimitedRecords(text, delim);
        if (records.isEmpty()) {
            return;
        }
        if (hasHeader) {
            List<String> columns = records.get(0).cells();
            tables.add(new Table(fallbackTitle, columns,
                    new ArrayList<>(records.subList(1, records.size()))));
        } else {
            tables.add(new Table(fallbackTitle, List.of(), records));
        }
    }

    /**
     * RFC4180 记录解析: 引号内分隔符与换行不算边界({@code ""} 转义为一个引号);
     * 多行单元格内的换行折成空格(保持"一行一块"的行语义); 全空记录跳过; 行号 1 基。
     */
    private List<Row> parseDelimitedRecords(String text, char delim) {
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int lineNo = 1;
        int rowStartLine = 1;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : '\n'; // 末尾补记录终止符, 收尾最后一行
            if (c == '"') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++; // "" → "
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == '\n' && inQuotes) {
                cell.append(' ');
                lineNo++;
            } else if (c == '\n' || (!inQuotes && c == delim)) {
                cells.add(cell.toString().strip());
                cell.setLength(0);
                if (c == '\n') {
                    if (cells.stream().anyMatch(v -> !v.isEmpty())) {
                        rows.add(new Row(rowStartLine, new ArrayList<>(cells)));
                    }
                    cells.clear();
                    lineNo++;
                    rowStartLine = lineNo;
                }
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        return rows;
    }

    /** 分隔符嗅探: 首个非空行的引号外出现次数最多者({@code , \t ; |}), 全 0 按逗号(单列表) */
    private char sniffDelimiter(String text) {
        String firstLine = "";
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) {
                firstLine = line;
                break;
            }
        }
        char best = ',';
        int bestCount = 0;
        for (char cand : new char[]{',', '\t', ';', '|'}) {
            int count = countOutsideQuotes(firstLine, cand);
            if (count > bestCount) {
                bestCount = count;
                best = cand;
            }
        }
        return best;
    }

    private int countOutsideQuotes(String line, char cand) {
        int count = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == cand && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    /** 单元格切分(Excel 形状): {@code \t} 分隔; 空单元格保留占位(渲染层跳过) */
    private List<String> splitCells(String line, char delim) {
        List<String> cells = new ArrayList<>();
        for (String cell : line.split(String.valueOf(delim), -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    /** 渲染一行: {@code 标题 第N行: 列名=值 | 列名=值}(空单元格跳过; 超出表头的列用"列N") */
    private String render(Table table, Row row) {
        StringBuilder sb = new StringBuilder();
        if (table.title() != null && !table.title().isBlank()) {
            sb.append(table.title()).append(' ');
        }
        sb.append("第").append(row.lineNo()).append("行: ");
        boolean wrote = false;
        List<String> cells = row.cells();
        for (int i = 0; i < cells.size(); i++) {
            String value = cells.get(i);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (wrote) {
                sb.append(" | ");
            }
            String column = i < table.columns().size() && !table.columns().get(i).isBlank()
                    ? table.columns().get(i)
                    : "列" + (i + 1);
            sb.append(column).append('=').append(value);
            wrote = true;
        }
        return wrote ? sb.toString() : "";
    }

    /** 表标题回退: Tika 把源文件放进 metadata.source(CSV/TSV 无 sheet 名, 用文件名主体) */
    private String titleOf(Document fullDoc) {
        Object src = fullDoc.getMetadata().get("source");
        String name = src == null ? "" : src.toString();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isBlank() ? null : name;
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
