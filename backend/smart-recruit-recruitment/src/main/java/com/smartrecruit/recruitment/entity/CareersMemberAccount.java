package com.smartrecruit.recruitment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Offer搭子会员账户（课程演示）。
 *
 * <p>对应表 {@code careers_member_account}，一个用户至多一条记录。</p>
 */
@Data
@TableName("careers_member_account")
public class CareersMemberAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 系统用户 ID */
    private Long userId;

    /** 会员等级：FREE / PRO / PREMIUM */
    private String level;

    /** 会员状态：ACTIVE / EXPIRED */
    private String status;

    /** 会员开始时间 */
    private LocalDateTime startTime;

    /** 会员到期时间 */
    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
