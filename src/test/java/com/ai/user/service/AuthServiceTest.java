package com.ai.user.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.ai.user.dto.RegisterRequest;
import com.ai.user.mapper.SysUserMapper;
import com.ai.user.security.JwtTokenProvider;
import com.ai.user.security.LoginAttemptLimiter;
import com.ai.user.security.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} 注册开关测试(部署演示闸门): 关闭后公开注册端点必须友好拒绝(6007),
 * 不触碰数据库——注册无节流, 开放等于任何人可自助建号刷对话量。
 */
class AuthServiceTest {

    private SysUserMapper userMapper;
    private AppProperties props;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        props = new AppProperties();
        service = new AuthService(userMapper, mock(JwtTokenProvider.class),
                mock(LoginAttemptLimiter.class), mock(TokenBlacklistService.class), props);
    }

    private static RegisterRequest request(String username) {
        return new RegisterRequest(username, "Password123", "昵称");
    }

    @Test
    void registerRejectedWith6007WhenDisabled() {
        props.getAuth().setRegistrationEnabled(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.register(request("someone")));
        assertEquals(ErrorCode.REGISTRATION_DISABLED, e.getErrorCode());
        verifyNoInteractions(userMapper);
    }

    @Test
    void registerGatePassesWhenEnabled() {
        // 开关开着时必须走到既有校验(此处以"用户名已存在"证明闸门已过, 不触达建号/token 侧)
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.register(request("admin")));
        assertEquals(ErrorCode.USER_EXISTS, e.getErrorCode(), "闸门放行后到达既有重名校验");
        verify(userMapper).selectCount(any());
    }

    /* ---------------- 演示一键登录(登录页"演示账号"按钮, 免密) ---------------- */

    private com.ai.user.entity.SysUser demoUser(int status) {
        com.ai.user.entity.SysUser u = new com.ai.user.entity.SysUser();
        u.setId(86L);
        u.setUsername("demo");
        u.setRole("DEMO");
        u.setStatus(status);
        return u;
    }

    @Test
    void demoLoginIssuesTokenForConfiguredAccount() {
        when(userMapper.selectOne(any())).thenReturn(demoUser(1));

        service.demoLogin();

        verify(userMapper).selectOne(any());
        // buildAuthVO 走真实 JwtTokenProvider(随机密钥即可签发), 到这里未抛即成功
    }

    @Test
    void demoLoginRejectedWhenAccountMissing() {
        when(userMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> service.demoLogin());
        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode(), "演示账号未建时给可操作的指引而非裸 500");
    }

    @Test
    void demoLoginRejectedWhenAccountDisabled() {
        when(userMapper.selectOne(any())).thenReturn(demoUser(0));

        BusinessException e = assertThrows(BusinessException.class, () -> service.demoLogin());
        assertEquals(ErrorCode.USER_DISABLED, e.getErrorCode());
    }

    @Test
    void demoLoginRejectedWhenSwitchedOff() {
        props.getAuth().setDemoLoginEnabled(false);

        BusinessException e = assertThrows(BusinessException.class, () -> service.demoLogin());
        assertEquals(ErrorCode.AUTH_FAILED, e.getErrorCode());
        verifyNoInteractions(userMapper);
    }
}
