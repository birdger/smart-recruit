package com.smartrecruit.recruitment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 模拟开通会员请求。
 *
 * <p>只接受服务端已定义的套餐编码，不接受任何金额、有效期、用户 ID 等客户端自述信息。</p>
 */
@Data
public class ActivateMembershipRequest {

    @NotBlank(message = "请选择会员套餐")
    @Pattern(regexp = "PRO|PREMIUM", message = "仅支持开通普通会员或高级会员")
    private String planCode;
}
