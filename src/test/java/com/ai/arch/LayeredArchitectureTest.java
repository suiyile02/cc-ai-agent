package com.ai.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.ai.user.security.RequireAdmin;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 架构规范守护(模块优先分层, 规范见 AGENTS.md「项目架构规范」)：
 * <ul>
 *   <li>模块切片(chat/knowledge/user/session/system/rag/context/agent/memory/aspect/common/config/prompt)
 *       之间禁止循环依赖; config 为装配根, 对任何模块的依赖豁免; </li>
 *   <li>common 为纯工具层, 不得反向依赖任何业务模块; </li>
 *   <li>entity 为纯数据载体, 不得依赖 service/controller/aspect; </li>
 *   <li>service 禁止依赖 controller; </li>
 *   <li>Controller 禁止直连 Mapper(必须经 Service); </li>
 *   <li>Controller 只能位于各模块的 controller 子包; </li>
 *   <li>chat 是顶层编排模块: 除自身与 system(消费审计事件)外, 任何模块不得依赖它; </li>
 *   <li>模块的 dto 子包是内部细节, 不得被其它模块引用(跨模块契约一律放模块根包); </li>
 *   <li>entity 包内不得定义枚举(被共享的领域枚举属模块根包); </li>
 *   <li>审计表实体 system.entity 只有 system 与写入切面 aspect 可以触碰; </li>
 *   <li>知识库写方法（upload/delete/reprocess）必须标 @RequireAdmin。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.ai", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

    /** 业务模块切片不允许循环依赖(装配根 config 的出边全部豁免)。
     *  两条规则互补: 一条覆盖模块根包类(契约/枚举), 一条覆盖模块子包类(controller/service/...) */
    @ArchTest
    static final ArchRule rootPackageClassesShouldBeFreeOfCycles =
            slices().matching("com.ai.(*)")
                    .should().beFreeOfCycles()
                    .ignoreDependency(
                            resideInAPackage("com.ai.config.."),
                            resideInAPackage("com.ai.."));

    @ArchTest
    static final ArchRule subPackageClassesShouldBeFreeOfCycles =
            slices().matching("com.ai.(*).(**)")
                    .should().beFreeOfCycles()
                    .ignoreDependency(
                            resideInAPackage("com.ai.config.."),
                            resideInAPackage("com.ai.."));

    /** common 只能依赖自身(JDK/三方库), 不得依赖任何业务模块 */
    @ArchTest
    static final ArchRule commonShouldNotDependOnModules =
            noClasses().that().resideInAPackage("com.ai.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.ai.chat..", "com.ai.knowledge..", "com.ai.session..",
                            "com.ai.system..", "com.ai.user..", "com.ai.rag..",
                            "com.ai.context..", "com.ai.agent..", "com.ai.memory..",
                            "com.ai.aspect..", "com.ai.config..", "com.ai.prompt..",
                            "com.ai.knowledge.mapper..");

    /** entity 是纯数据载体, 不依赖业务逻辑层 */
    @ArchTest
    static final ArchRule entitiesShouldNotDependOnLogicLayers =
            noClasses().that().resideInAPackage("..entity..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..service..", "..controller..", "com.ai.aspect..");

    /** service 不允许依赖 controller */
    @ArchTest
    static final ArchRule servicesShouldNotDependOnControllers =
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..");

    /** Controller 只做请求映射/参数绑定, 禁止直接访问 Mapper(必须经 Service) */
    @ArchTest
    static final ArchRule controllersShouldNotAccessMappers =
            noClasses().that().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().resideInAPackage("..mapper..");

    /** Controller 必须位于模块的 controller 子包 */
    @ArchTest
    static final ArchRule controllersShouldResideInControllerPackage =
            noClasses().that().areAnnotatedWith(RestController.class)
                    .should().resideInAnyPackage(
                            "com.ai", "com.ai.chat", "com.ai.knowledge", "com.ai.user",
                            "com.ai.session", "com.ai.system", "com.ai.rag", "com.ai.context",
                            "com.ai.agent", "com.ai.memory", "com.ai.common", "com.ai.aspect",
                            "com.ai.config", "com.ai.prompt",
                            "com.ai.chat.service..", "com.ai.knowledge.service..",
                            "com.ai.user.service..", "com.ai.user.security..",
                            "com.ai.session.service..", "com.ai.system.service..");

    /**
     * chat 是顶层编排模块, 只允许被自身与 system(消费其审计事件)依赖。
     * 反向依赖(rag→chat 曾发生)会让下层能力模块绑死编排层, 无法独立复用与测试。
     */
    @ArchTest
    static final ArchRule chatShouldNotBeDependedOnFromBelow =
            noClasses().that().resideOutsideOfPackages("com.ai.chat..", "com.ai.system..")
                    .should().dependOnClassesThat().resideInAPackage("com.ai.chat..");

    /** 模块的 dto 是内部细节: 跨模块传递的类型必须放模块根包(对外契约位) */
    @ArchTest
    static final ArchRule dtosShouldStayInsideTheirModule =
            noClasses().that().resideOutsideOfPackage("com.ai.chat..")
                    .should().dependOnClassesThat().resideInAPackage("com.ai.chat.dto..");

    /** 领域枚举不得寄生在 ORM 实体包内(否则共享它概念的人被迫依赖实体与持久化框架) */
    @ArchTest
    static final ArchRule enumsShouldNotLiveInEntityPackages =
            noClasses().that().resideInAnyPackage("..entity..", "..entity")
                    .should().beEnums();

    /** 审计表实体只有 system 模块与写日志的 aspect 可以触碰 */
    @ArchTest
    static final ArchRule auditEntitiesShouldStayInSystem =
            noClasses().that().resideOutsideOfPackages("com.ai.system..", "com.ai.aspect..")
                    .should().dependOnClassesThat().resideInAPackage("com.ai.system.entity..");

    /**
     * 知识库的写操作（上传/删除/重处理）一律要 {@code @RequireAdmin}——知识库是全公司共享的
     * RAG 内容源，任何登录用户能改就等于能往同事的答案里塞材料。
     *
     * <p>注解刻意放在 **Service 方法**上（与既有实现一致），因此这里也按 Service 方法校验；
     * 新增写方法若漏标，本规则直接失败。注意 Spring AOP 不拦截自调用：
     * {@code uploadBatch} 内部直调 {@code upload}，两个方法都必须各自带上注解，
     * 否则批量入口会绕过校验（详见该方法 javadoc）。
     *
     * <p>范围刻意只到 {@code KnowledgeDocumentService}：同包的 {@code FileStorageService.delete}
     * 是磁盘工具方法（由已受控的 {@code deleteDocument} 调用），不是对外授权面，纳入只会误报。
     */
    @ArchTest
    static final ArchRule knowledgeWritesShouldRequireAdmin =
            methods().that().arePublic()
                    .and().areDeclaredInClassesThat().haveSimpleName("KnowledgeDocumentService")
                    .and().haveNameMatching("(upload|delete|reprocess).*")
                    .should().beAnnotatedWith(RequireAdmin.class)
                    .as("知识库写操作必须标注 @RequireAdmin");
}
