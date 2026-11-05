package com.smartrecruit.recruitment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Offer搭子 AI 求职匹配请求。
 */
@Data
public class AiMatchRequest {

    /** 简历文本（必填，长度上限 4000） */
    @NotBlank(message = "简历文本不能为空")
    @Size(max = 4000, message = "简历文本不能超过 4000 字")
    private String resumeText;

    /** 岗位 JD 全文（必填，长度上限 4000） */
    @NotBlank(message = "岗位 JD 不能为空")
    @Size(max = 4000, message = "岗位 JD 不能超过 4000 字")
    private String jobDescription;
}
