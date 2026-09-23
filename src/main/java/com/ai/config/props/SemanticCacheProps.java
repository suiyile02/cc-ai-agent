package com.ai.config.props;

import lombok.Data;

/**
 * 语义缓存({@code app.semantic-cache.*}): 相同知识库问题的回答缓存(P3-3, 命中跳过检索+模型调用)。
 */
@Data
public class SemanticCacheProps {

    /** 是否启用 */
    private boolean enabled = true;
    /** 正缓存有效期(小时); 知识库文档上传/删除/重处理会使全部缓存立即失效 */
    private int ttlHours = 24;
    /** 负缓存有效期(分钟): 检索零命中的问题短时间视为无答案, 防穿透反复打检索+模型 */
    private int missTtlMinutes = 30;
}
