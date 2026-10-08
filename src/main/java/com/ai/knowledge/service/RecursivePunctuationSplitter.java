package com.ai.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归标点切分(包内共享工具): 按标点层级(句末标点 → 逗号/顿号 → 空格 → 硬切)把文本切成
 * 不超过目标字符数的片段, 块尾尽量停在完整标点处; 全部分隔符都停不下时退化为固定宽硬切
 * (保进度、不丢内容)。迭代实现, 防大文档递归栈溢出。
 *
 * <p>只负责"怎么切", 不读配置、不产 Document; 由 {@link ChineseRecursiveChunker} 与
 * {@link MdStructureChunker} 共用(两处需要同一套回退语义)。
 */
final class RecursivePunctuationSplitter {

    /** 分隔符层级: 从大到小, 前面的优先级更高(块尾优先停在这里) */
    private static final String[][] SEPARATORS = {
            {"。", "！", "？", "；"},
            {"，", "、", ":"},
            {" ", "\u3000"},
    };

    private RecursivePunctuationSplitter() {
    }

    /**
     * 把文本切成不超过 targetChars 的片段。
     *
     * @param text        原文(可为空)
     * @param targetChars 目标片段字符数(≤0 按 1 处理)
     * @return 片段列表(已 trim 的非空片段; 仅空白字符会归一, 实质内容不丢)
     */
    static List<String> split(String text, int targetChars) {
        List<String> pieces = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return pieces;
        }
        int target = Math.max(1, targetChars);
        int start = 0;
        while (start < text.length()) {
            int remaining = text.length() - start;
            if (remaining <= target) {
                String tailPiece = text.substring(start).trim();
                if (!tailPiece.isEmpty()) {
                    pieces.add(tailPiece);
                }
                break;
            }
            int cut = findCut(text, start, target, 0);
            if (cut <= start) {
                // 全部分隔符都无法在窗口内断开 → 退化为固定字符宽硬切(兜底, 保进度)
                cut = Math.min(text.length(), start + target);
            }
            String piece = text.substring(start, cut).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            start = cut;
        }
        return pieces;
    }

    /** 在 [start, start+targetChars] 内找最后一个可停分隔符位置(递归加深层级, 最多 3 层) */
    private static int findCut(String text, int start, int targetChars, int level) {
        if (level >= SEPARATORS.length) {
            return -1;
        }
        int windowEnd = Math.min(text.length(), start + targetChars);
        int best = -1;
        for (String sep : SEPARATORS[level]) {
            int idx = text.indexOf(sep, start);
            while (idx != -1 && idx < windowEnd) {
                best = Math.max(best, idx + sep.length());
                idx = text.indexOf(sep, idx + sep.length());
            }
        }
        if (best == -1) {
            return findCut(text, start, targetChars, level + 1);
        }
        return best;
    }
}
