package com.smartrecruit.aiengine.dto.request;

import com.smartrecruit.aiengine.domain.OnboardingCandidate;

/**
 * 新员工留存风险预测请求。
 *
 * @param candidate 入职候选人画像
 * @since 2026-08-10
 */
public record RetentionPredictRequest(OnboardingCandidate candidate) {
}
