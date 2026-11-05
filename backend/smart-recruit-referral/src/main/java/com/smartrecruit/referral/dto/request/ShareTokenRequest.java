package com.smartrecruit.referral.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 生成分享令牌请求。
 *
 * @author 苏三
 * @since 2026-08-01
 */
@Data
public class ShareTokenRequest {

    @NotNull(message = "Program ID is required")
    private Long programId;

    private String referrerName;

    private String source;
}
