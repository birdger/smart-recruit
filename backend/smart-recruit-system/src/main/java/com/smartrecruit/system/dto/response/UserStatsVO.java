package com.smartrecruit.system.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户统计 VO。
 *
 * @since 2026-07-10
 */
public record UserStatsVO(
        @JsonProperty("total") long total,
        @JsonProperty("active") long active,
        @JsonProperty("frozen") long frozen
) {
}
