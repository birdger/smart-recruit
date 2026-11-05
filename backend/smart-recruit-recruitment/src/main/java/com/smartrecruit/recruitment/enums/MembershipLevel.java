package com.smartrecruit.recruitment.enums;

import java.util.Locale;

/**
 * 会员等级与每日 AI 匹配额度（课程演示配置，集中管理便于调整）。
 *
 * <p>额度按 {@code Asia/Shanghai} 自然日重置。演示价格仅用于产品展示，
 * 不代表任何真实商业定价。</p>
 */
public enum MembershipLevel {

    /** 免费用户：每日 3 次 AI 匹配 */
    FREE("免费用户", 0, 3, 0, false),

    /** 普通会员：每日 20 次 AI 匹配，演示价格 29 元/月 */
    PRO("普通会员", 29, 20, 30, true),

    /** 高级会员：每日 100 次 AI 匹配，演示价格 69 元/月 */
    PREMIUM("高级会员", 69, 100, 30, false);

    private final String displayName;
    private final int demoPrice;
    private final int dailyLimit;
    private final int durationDays;
    private final boolean recommended;

    MembershipLevel(String displayName, int demoPrice, int dailyLimit,
                    int durationDays, boolean recommended) {
        this.displayName = displayName;
        this.demoPrice = demoPrice;
        this.dailyLimit = dailyLimit;
        this.durationDays = durationDays;
        this.recommended = recommended;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDemoPrice() {
        return demoPrice;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public boolean isRecommended() {
        return recommended;
    }

    /**
     * 解析等级编码，非法值统一降级为 {@link #FREE}。
     */
    public static MembershipLevel of(String code) {
        if (code == null || code.isBlank()) {
            return FREE;
        }
        try {
            return MembershipLevel.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FREE;
        }
    }
}
