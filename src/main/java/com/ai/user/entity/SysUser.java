package com.ai.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 系统用户(sys_user)：注册/登录鉴权的主体。
 * status: 1=正常 0=禁用。表结构见 db/schema 脚本。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名(唯一, 3~32 位字母数字下划线) */
    private String username;

    /** 密码(PBKDF2 加盐哈希串, 不存明文) */
    private String password;

    /** 昵称(展示名, 可空) */
    private String nickname;

    /** 角色: ADMIN=管理员 USER=普通用户(默认) */
    private String role = "USER";

    /** 状态: 1=正常 0=禁用 */
    private Integer status = 1;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间(自动填充) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
