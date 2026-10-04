package com.ai.system.service;

import com.ai.memory.ChatMemoryArchive;
import com.ai.system.dto.MemoryRawEntryVO;
import com.ai.user.security.RequireAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话原始轨迹留档查询(管理员)：回放"模型当时看到的完整对话与压缩事件"。
 *
 * <p>留档含对话全文, 敏感度高于四类审计日志, 刻意只对管理员开放(不做本人自查);
 * 查询不脱敏——这就是它存在的意义(精确回放), 访问面必须收窄到管理员。
 */
@Service
@RequiredArgsConstructor
public class MemoryRawService {

    private final ChatMemoryArchive archive;

    /**
     * 按序读取某会话的原始轨迹留档。
     *
     * @param sessionId 会话 ID
     * @param limit     最多返回条数(缺省 500, 上限 2000)
     * @return 留档条目(升序)
     */
    @RequireAdmin
    public List<MemoryRawEntryVO> list(String sessionId, Integer limit) {
        int effective = limit == null || limit < 1 ? 500 : Math.min(limit, 2000);
        return archive.findByConversationId(sessionId, effective).stream()
                .map(e -> new MemoryRawEntryVO(e.seq(), e.role(), e.content(), e.batch(), e.createdAt()))
                .toList();
    }
}
