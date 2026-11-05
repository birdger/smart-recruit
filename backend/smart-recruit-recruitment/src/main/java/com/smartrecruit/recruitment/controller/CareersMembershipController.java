package com.smartrecruit.recruitment.controller;

import com.smartrecruit.common.dto.ApiResponse;
import com.smartrecruit.common.exception.AuthenticationException;
import com.smartrecruit.recruitment.dto.request.ActivateMembershipRequest;
import com.smartrecruit.recruitment.dto.response.MembershipActivateVO;
import com.smartrecruit.recruitment.dto.response.MembershipPlanVO;
import com.smartrecruit.recruitment.dto.response.MembershipVO;
import com.smartrecruit.recruitment.service.MembershipService;
import com.smartrecruit.recruitment.util.UserContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Offer搭子 · 会员中心接口（课程演示）。
 *
 * <p>所有接口均要求真实登录身份（由 JWT 解析得到，不信任客户端提交的用户信息）。</p>
 */
@RestController
@RequestMapping("/api/v1/careers/membership")
@RequiredArgsConstructor
public class CareersMembershipController {

    private final MembershipService membershipService;

    /** 查询当前会员信息与今日 AI 匹配额度 */
    @GetMapping("/me")
    public ApiResponse<MembershipVO> me() {
        return ApiResponse.success(membershipService.getMembership(currentUserId()));
    }

    /** 查询三档会员套餐 */
    @GetMapping("/plans")
    public ApiResponse<List<MembershipPlanVO>> plans() {
        return ApiResponse.success(membershipService.listPlans());
    }

    /** 模拟开通会员（不产生真实支付） */
    @PostMapping("/activate")
    public ApiResponse<MembershipActivateVO> activate(@Valid @RequestBody ActivateMembershipRequest request) {
        return ApiResponse.success(membershipService.activate(currentUserId(), request.getPlanCode()));
    }

    private Long currentUserId() {
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            throw AuthenticationException.tokenMissing();
        }
        return userId;
    }
}
