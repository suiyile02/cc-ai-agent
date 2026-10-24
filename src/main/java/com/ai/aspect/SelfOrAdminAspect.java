package com.ai.aspect;
import com.ai.user.security.RequireAdmin;
import com.ai.user.security.RequireRoles;
import com.ai.user.security.RequireSelfOrAdmin;
import com.ai.user.security.UserContext;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 授权切面(项目唯一的角色判定入口, 三个注解共用本类)：
 * <ol>
 *   <li>{@link RequireSelfOrAdmin}——"管理员或本人"的查询范围控制: 管理员放行原参数,
 *       非管理员把 {@code userIdParam} 参数强制改写为本人 ID(数据隔离由 Service 层过滤完成);</li>
 *   <li>{@link RequireAdmin}——仅 ADMIN 放行;</li>
 *   <li>{@link RequireRoles}——角色在声明列表内放行(如 ADMIN+DEMO 共用的操作)。</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
public class SelfOrAdminAspect {

    /**
     * 环绕增强：按角色决定放行或强制改写 userId 参数。
     *
     * @param pjp                连接点(被注解的 Handler 方法)
     * @param requireSelfOrAdmin 注解(含 userId 参数名)
     * @return 目标方法返回值(透传)
     * @throws Throwable 目标方法异常(透传)
     */
    @Around("@annotation(requireSelfOrAdmin)")
    public Object enforce(ProceedingJoinPoint pjp, RequireSelfOrAdmin requireSelfOrAdmin)
            throws Throwable {
        UserContext.CurrentUser current = UserContext.get();
        if (current == null) {
            // 拦截器正常工作时不会到达; 防御性兜底
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (current.isAdmin()) {
            return pjp.proceed();
        }

        String param = requireSelfOrAdmin.userIdParam();
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = pjp.getArgs();
        if (names == null) {
            // 未编译参数名(-parameters)时无法定位, 一律拒绝, 保证 fail-closed
            log.error("授权切面无法解析方法参数名: {}",
                    signature.getMethod().getDeclaringClass().getSimpleName());
            throw new BusinessException(ErrorCode.AUTH_FAILED, "需要管理员权限");
        }
        boolean found = false;
        for (int i = 0; i < names.length; i++) {
            if (param.equals(names[i])) {
                args[i] = current.userId();
                found = true;
                break;
            }
        }
        if (!found) {
            log.error("授权切面未找到参数 {}: {}", param,
                    signature.getMethod().getDeclaringClass().getSimpleName());
            throw new BusinessException(ErrorCode.AUTH_FAILED, "需要管理员权限");
        }
        return pjp.proceed(args);
    }

    /**
     * 环绕增强：处理 {@link RequireAdmin} 注解——仅 ADMIN 角色放行,
     * 普通用户(含未带角色的开发模拟身份)一律拒绝。
     *
     * @param pjp          连接点(被注解的方法)
     * @param requireAdmin 注解
     * @return 目标方法返回值(透传)
     * @throws Throwable 目标方法异常(透传)
     */
    @Around("@annotation(requireAdmin)")
    public Object enforceAdmin(ProceedingJoinPoint pjp, RequireAdmin requireAdmin)
            throws Throwable {
        UserContext.CurrentUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (!current.isAdmin()) {
            log.warn("非管理员访问管理员操作: user={}", current.username());
            throw new BusinessException(ErrorCode.AUTH_FAILED, "该操作需要管理员权限");
        }
        return pjp.proceed();
    }

    /**
     * 环绕增强：处理 {@link RequireRoles} 注解——角色在声明列表内放行, 其余一律拒绝。
     *
     * <p>未登录/角色不匹配都拒绝(fail-closed); 角色串为空数组同样拒绝——
     * 注解写错({@code @RequireRoles({}})时的安全倾向必须是"全拒"而非"全放"。
     *
     * @param pjp           连接点(被注解的方法)
     * @param requireRoles  注解(允许的角色列表)
     * @return 目标方法返回值(透传)
     * @throws Throwable 目标方法异常(透传)
     */
    @Around("@annotation(requireRoles)")
    public Object enforceRoles(ProceedingJoinPoint pjp, RequireRoles requireRoles)
            throws Throwable {
        UserContext.CurrentUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (requireRoles.value().length == 0
                || Arrays.stream(requireRoles.value()).noneMatch(r -> r.equals(current.role()))) {
            log.warn("角色不匹配访问受限操作: user={}, role={}, 允许={}",
                    current.username(), current.role(), Arrays.toString(requireRoles.value()));
            throw new BusinessException(ErrorCode.AUTH_FAILED, "当前账号无权执行该操作");
        }
        return pjp.proceed();
    }
}
