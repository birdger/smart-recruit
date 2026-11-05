package com.smartrecruit.recruitment.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 当前用户的会员信息与 AI 匹配额度（服务端计算结果）。
 */
@Data
public class MembershipVO {

    /** 等级编码：FREE / PRO / PREMIUM */
    private String level;

    /** 等级名称：免费用户 / 普通会员 / 高级会员 */
    private String levelName;

    /** 状态：FREE（从未开通）/ ACTIVE（生效中）/ EXPIRED（已过期） */
    private String status;

    /** 是否拥有付费会员权益 */
    private Boolean active;

    /** 会员开始时间（yyyy-MM-dd HH:mm:ss，未开通为 null） */
    private String startTime;

    /** 会员到期时间（yyyy-MM-dd HH:mm:ss，未开通为 null） */
    private String expireTime;

    /** 剩余天数（未开通为 null） */
    private Long daysRemaining;

    /** 每日 AI 匹配额度上限 */
    private Integer dailyLimit;

    /** 今日已使用次数 */
    private Integer usedToday;

    /** 今日剩余次数 */
    private Integer remaining;

    /** 额度统计日期（yyyy-MM-dd） */
    private String usageDate;

    /** 下次额度重置时间（yyyy-MM-dd HH:mm:ss） */
    private String resetAt;

    /** 当前等级权益说明 */
    private List<String> perks;
}
