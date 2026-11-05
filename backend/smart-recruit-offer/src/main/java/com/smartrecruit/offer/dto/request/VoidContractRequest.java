package com.smartrecruit.offer.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 合同作废请求。
 *
 * @param reason 作废原因（必填）
 * @since 2026-08-09
 */
public record VoidContractRequest(@NotBlank(message = "作废原因不能为空") String reason) {
}
