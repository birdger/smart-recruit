package com.smartrecruit.recruitment.dto.response;

import lombok.Data;

/**
 * 会员套餐中的单条权益。
 */
@Data
public class PlanFeatureVO {

    /** 权益描述 */
    private String text;

    /** 是否为规划中功能（true 表示尚未上线，仅作产品展示） */
    private Boolean planning;
}
