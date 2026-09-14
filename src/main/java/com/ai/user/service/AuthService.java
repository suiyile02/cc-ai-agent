package com.ai.user.service;
import com.ai.user.security.JwtTokenProvider;
import com.ai.user.security.PasswordHasher;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.user.dto.AuthVO;
import com.ai.user.dto.LoginRequest;
import com.ai.user.dto.RegisterRequest;
import com.ai.user.dto.UserVO;
import com.ai.user.entity.SysUser;
import com.ai.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户鉴权业务：注册 / 登录 / 获取当前用户。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final JwtTokenProvider tokenProvider;
    private final com.ai.user.security.LoginAttemptLimiter loginAttemptLimiter;

    /**
     * 注册新用户并返回自动登录态。
     *
     * @param request 注册请求(用户名/密码/昵称)
     * @return AuthVO(token + user)
     * @throws BusinessException USER_EXISTS
     */
    @Transactional
    public AuthVO register(RegisterRequest request) {
        String username = request.username().trim();
        if (existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USER_EXISTS);
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordHasher.encode(request.password()));
        user.setNickname(request.effectiveNickname());
        user.setStatus(1);
        userMapper.insert(user);
        return buildAuthVO(user);
    }

    /**
     * 登录校验并签发令牌。
     *
     * @param request 登录请求
     * @return AuthVO(token + user)
     * @throws BusinessException 用户名/密码错误(6003)或账号被禁用(6004)
     */
    @Transactional(readOnly = true)
    public AuthVO login(com.ai.user.security.LoginContext ctx) {
        LoginRequest request = ctx.request();
        // 登录失败限流: 锁定期内直接拒绝(不查库不比对, 防暴力破解)
        if (loginAttemptLimiter.isLocked(request.username().trim(), ctx.clientIp())) {
            long remain = loginAttemptLimiter.remainingLockMs(request.username().trim(), ctx.clientIp());
            throw new BusinessException(ErrorCode.LOGIN_LOCKED,
                    "登录失败次数过多, 已临时锁定, 请 " + ((remain / 60_000) + 1) + " 分钟后再试");
        }
        SysUser user = findByUsername(request.username().trim());
        if (user == null || !PasswordHasher.matches(request.password(), user.getPassword())) {
            loginAttemptLimiter.recordFailure(request.username().trim(), ctx.clientIp());
            throw new BusinessException(ErrorCode.USERNAME_OR_PASSWORD_ERROR);
        }
        loginAttemptLimiter.recordSuccess(request.username().trim(), ctx.clientIp());
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return buildAuthVO(user);
    }

    /**
     * 获取当前登录用户信息。
     *
     * @param userId 用户 ID
     * @return 用户信息 VO
     * @throws BusinessException USER_NOT_FOUND
     */
    @Transactional(readOnly = true)
    public UserVO me(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return toVO(user);
    }

    /**
     * 组装登录态响应。
     *
     * @param user 用户实体
     * @return AuthVO
     */
    private AuthVO buildAuthVO(SysUser user) {
        String token = tokenProvider.createToken(user.getId(), user.getUsername(), user.getRole());
        return new AuthVO(token, toVO(user));
    }

    /**
     * 查询用户名是否已存在(注册查重)。
     *
     * @param username 登录名
     * @return true=已存在
     */
    private boolean existsByUsername(String username) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0;
    }

    /**
     * 按登录名查询用户。
     *
     * @param username 登录名
     * @return 用户, 不存在返回 null
     */
    private SysUser findByUsername(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    /**
     * 实体 → VO(禁止返回实体)。
     *
     * @param user 用户实体
     * @return 用户 VO
     */
    private UserVO toVO(SysUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(),
                user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
