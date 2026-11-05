package com.smartrecruit.recruitment.dto.response;

import lombok.Data;

/**
 * 模拟开通会员的结果（课程演示，未发生真实支付）。
 */
@Data
public class MembershipActivateVO {

    /** 模拟订单号 */
    private String orderNo;

    /** 套餐编码 */
    private String planCode;

    /** 套餐名称 */
    private String planName;

    /** 演示金额（元） */
    private Integer amount;

    /** 是否为演示订单 */
    private Boolean demo;

    /** 是否已支付（演示环境恒为 false） */
    private Boolean paid;

    /** 订单创建时间 */
    private String createdAt;

    /** 开通后的会员信息 */
    private MembershipVO membership;
}
