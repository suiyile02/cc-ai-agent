package com.ai.aspect;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.user.security.RequireAdmin;
import com.ai.user.security.RequireSelfOrAdmin;
import com.ai.user.security.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 授权切面单元测试：{@code RequireSelfOrAdmin}(管理员放行/非管理员强制改写/fail-closed)
 * 与 {@code RequireAdmin}(仅管理员放行)。
 */
class SelfOrAdminAspectTest {

    /** 供反射获取注解实例的样例方法 */
    @SuppressWarnings("unused")
    static class Sample {

        @RequireSelfOrAdmin(userIdParam = "userId")
        public void handler(Long userId) {
        }
    }

    private final SelfOrAdminAspect aspect = new SelfOrAdminAspect();
    private ProceedingJoinPoint pjp;
    private RequireSelfOrAdmin annotation;

    @BeforeEach
    void setUp() throws Exception {
        pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = Sample.class.getDeclaredMethod("handler", Long.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"userId"});
        when(pjp.getSignature()).thenReturn(signature);
        annotation = method.getAnnotation(RequireSelfOrAdmin.class);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Object[] givenArgs(Long userId) {
        Object[] args = new Object[]{userId};
        when(pjp.getArgs()).thenReturn(args);
        return args;
    }

    @Test
    void adminKeepsOriginalParam() throws Throwable {
        UserContext.set(new UserContext.CurrentUser(1L, "admin", UserContext.ROLE_ADMIN));
        Object[] args = givenArgs(9L);
        when(pjp.proceed()).thenReturn("ok"); // 管理员走无参 proceed 原样放行

        assertEquals("ok", aspect.enforce(pjp, annotation));
        verify(pjp).proceed();
        assertArrayEquals(new Object[]{9L}, args); // 原参数未被改写
    }

    @Test
    void otherUsersParamIsRewrittenToSelf() throws Throwable {
        UserContext.set(new UserContext.CurrentUser(2L, "user", UserContext.ROLE_USER));
        givenArgs(9L);
        when(pjp.proceed(new Object[]{2L})).thenReturn("ok");

        assertEquals("ok", aspect.enforce(pjp, annotation));
        verify(pjp).proceed(new Object[]{2L});
    }

    @Test
    void nullParamIsFilledWithSelf() throws Throwable {
        UserContext.set(new UserContext.CurrentUser(2L, "user", UserContext.ROLE_USER));
        givenArgs(null);
        when(pjp.proceed(new Object[]{2L})).thenReturn("ok");

        assertEquals("ok", aspect.enforce(pjp, annotation));
        verify(pjp).proceed(new Object[]{2L});
    }

    @Test
    void missingParamNameFailsClosed() {
        // 参数名不匹配时拒绝请求, 防止授权规则被静默跳过
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        when(signature.getParameterNames()).thenReturn(new String[]{"other"});
        UserContext.set(new UserContext.CurrentUser(2L, "user", UserContext.ROLE_USER));
        givenArgs(9L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> aspect.enforce(pjp, annotation));
        assertEquals(ErrorCode.AUTH_FAILED, e.getErrorCode());
    }

    @Test
    void adminPassesRequireAdmin() throws Throwable {
        UserContext.set(new UserContext.CurrentUser(1L, "admin", UserContext.ROLE_ADMIN));
        when(pjp.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.enforceAdmin(pjp, mock(RequireAdmin.class)));
    }

    @Test
    void nonAdminRejectedByRequireAdmin() throws Throwable {
        UserContext.set(new UserContext.CurrentUser(2L, "user", UserContext.ROLE_USER));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aspect.enforceAdmin(pjp, mock(RequireAdmin.class)));
        assertEquals(ErrorCode.AUTH_FAILED, e.getErrorCode());
    }

    @Test
    void missingContextRejectedByRequireAdmin() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> aspect.enforceAdmin(pjp, mock(RequireAdmin.class)));
        assertEquals(ErrorCode.TOKEN_INVALID, e.getErrorCode());
    }
}
