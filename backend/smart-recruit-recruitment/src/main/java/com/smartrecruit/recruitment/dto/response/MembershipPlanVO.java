package com.smartrecruit.recruitment.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 会员套餐（课程演示价格，不代表真实商业定价）。
 */
@Data
public class MembershipPlanVO {

    /** 等级编码：FREE / PRO / PREMIUM */
    private String code;

    /** 等级名称 */
    private String name;

    /** 演示价格（元/月），0 表示免费 */
    private Integer price;

    /** 价格说明 */
    private String priceNote;

    /** 模拟开通有效期天数 */
    private Integer durationDays;

    /** 每日 AI 匹配额度 */
    private Integer dailyLimit;

    /** 是否为推荐档位 */
    private Boolean recommended;

    /** 权益列表 */
    private List<PlanFeatureVO> features;
}
