package com.ai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * MyBatis-Plus 手工装配配置。
 *
 * <p>说明：Spring Boot 4 下 MP 官方 Boot3 自动装配未启用，这里直接手工创建
 * SqlSessionFactory/SqlSessionTemplate + Mapper 扫描 + 审计字段自动填充 + 分页插件，
 * 并保留 @ConditionalOnMissingBean 以便将来自动装配可用时自动回退、不重复注册。
 */
@Configuration
@MapperScan({
        "com.ai.knowledge.mapper", "com.ai.user.mapper", "com.ai.session.mapper",
        "com.ai.system.mapper", "com.ai.agent.mapper", "com.ai.memory.mapper",
        "com.ai.context.mapper"})
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 插件(分页)。
     *
     * @return 插件容器
     */
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 审计字段自动填充处理器(createdAt/updatedAt)。
     *
     * @return MetaObjectHandler
     */
    @Bean
    @ConditionalOnMissingBean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class,
                        LocalDateTime.now());
            }
        };
    }

    /**
     * 手工创建 MyBatis SqlSessionFactory(基于 MyBatis-Plus 的 FactoryBean)。
     *
     * @param dataSource         数据源
     * @param interceptor        MP 插件(分页)
     * @param metaObjectHandler  审计字段填充器
     * @return SqlSessionFactory
     * @throws Exception 构建失败
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
            MybatisPlusInterceptor interceptor, MetaObjectHandler metaObjectHandler)
            throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPlugins(interceptor);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        factoryBean.setGlobalConfig(globalConfig);

        // 手工装配不读取 yaml 的 mybatis-plus.configuration.*, 显式声明关键配置
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true); // snake_case 列 -> camelCase 属性
        factoryBean.setConfiguration(configuration);

        return factoryBean.getObject();
    }

    /**
     * 手工创建 SqlSessionTemplate。
     *
     * @param sqlSessionFactory 会话工厂
     * @return SqlSessionTemplate
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
