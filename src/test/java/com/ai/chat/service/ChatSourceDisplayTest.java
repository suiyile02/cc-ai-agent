package com.ai.chat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatSourceDisplay} 单元测试：文档名去扩展名去重、模型声明"未找到"的识别。
 */
class ChatSourceDisplayTest {

    @Test
    void stripsFileExtension() {
        assertEquals("员工手册示例", ChatSourceDisplay.stripExtension("员工手册示例.md"));
        assertEquals("报销制度", ChatSourceDisplay.stripExtension("报销制度.PDF"));
        assertEquals("noext", ChatSourceDisplay.stripExtension("noext"));
        assertEquals("a.b", ChatSourceDisplay.stripExtension("a.b.c")); // 只去最后一个后缀
    }

    @Test
    void extractsNamesDeduplicatedAndWithoutExtension() {
        List<String> names = ChatSourceDisplay.sourceNames(java.util.List.of());
        assertTrue(names.isEmpty());
    }

    @Test
    void detectsNoResultDeclaration() {
        assertTrue(ChatSourceDisplay.declaresNoResult("知识库中未找到相关信息。"));
        assertTrue(ChatSourceDisplay.declaresNoResult("抱歉，未找到相关资料"));
        assertTrue(ChatSourceDisplay.declaresNoResult("知识库未找到"));
        assertFalse(ChatSourceDisplay.declaresNoResult("根据《员工手册》，年假为 10 天。"));
        assertFalse(ChatSourceDisplay.declaresNoResult(null));
    }

}
