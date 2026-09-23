package com.ai.config.props;

import lombok.Data;

import java.util.List;

/** 跨域({@code app.cors.*}), 从 {@code AppProperties.Cors} 迁出。对接前端(Vue3)时放行的来源。 */
@Data
public class CorsProps {

    /** 允许跨域的来源列表; 为空则不注册 CORS 映射 */
    private List<String> allowedOrigins = List.of("http://localhost:5173");
}
