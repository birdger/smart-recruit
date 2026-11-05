package com.smartrecruit.recruitment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrecruit.common.dto.ApiResponse;
import com.smartrecruit.common.exception.AuthenticationException;
import com.smartrecruit.recruitment.client.OllamaClient;
import com.smartrecruit.recruitment.dto.request.AiMatchRequest;
import com.smartrecruit.recruitment.dto.response.AiMatchResultVO;
import com.smartrecruit.recruitment.dto.response.MembershipVO;
import com.smartrecruit.recruitment.service.MembershipService;
import com.smartrecruit.recruitment.util.UserContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Offer搭子 · AI 求职匹配接口。
 *
 * <p>接口要求真实登录身份，并在服务端按会员等级校验每日 AI 匹配额度：
 * 调用前原子预占一次额度，AI 失败时回滚，成功后才真正消耗额度。</p>
 */
@RestController
@RequestMapping("/api/v1/careers")
@RequiredArgsConstructor
@Slf4j
public class CareersAiController {

    private final OllamaClient ollamaClient;
    private final MembershipService membershipService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DISCLAIMER =
            "匹配分数为 AI 参考评估，不代表招聘结果预测。信息不足时模型可能无法给出完整判断。";

    /**
     * AI 求职匹配分析。
     *
     * POST /api/v1/careers/ai-match
     */
    @PostMapping("/ai-match")
    public ApiResponse<AiMatchResultVO> aiMatch(@Valid @RequestBody AiMatchRequest req) {

        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            throw AuthenticationException.tokenMissing();
        }

        // 服务端额度闸门：原子预占一次额度，额度用尽立即拒绝
        LocalDate quotaDate = membershipService.reserveAiQuota(userId);
        if (quotaDate == null) {
            log.info("ai-match 额度用尽: userId={}", userId);
            return ApiResponse.error(40029, "今日 AI 匹配额度已用完，开通会员可获得更多次数");
        }

        long start = System.currentTimeMillis();
        log.info("ai-match userId={}, resumeLen={}, jdLen={}",
                userId, req.getResumeText().length(), req.getJobDescription().length());

        AiMatchResultVO result;
        try {
            // 1. 构建 Prompt（严格约束 JSON 输出）
            String prompt = buildPrompt(req.getResumeText(), req.getJobDescription());

            // 2. 调用 Ollama
            String rawJson = ollamaClient.generateJson(prompt);

            // 3. 解析 JSON
            result = parseResult(rawJson);

            long latency = System.currentTimeMillis() - start;
            result.setLatencyMs(latency);
            result.setAiModel(ollamaClient.getModel());
            result.setDisclaimer(DISCLAIMER);

            log.info("ai-match OK → {}ms, score={}", latency, result.getScore());

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("ai-match FAIL → {}ms, err={}", latency, e.getMessage(), e);

            result = fallbackResult(e.getMessage());
            result.setLatencyMs(latency);
            result.setAiModel(ollamaClient.getModel());
            result.setDisclaimer(DISCLAIMER);
        }

        // 仅真实 AI 结果才消耗额度；失败降级（score 为 null）时回滚预占
        if (result.getScore() == null) {
            membershipService.releaseAiQuota(userId, quotaDate);
        }

        fillQuota(result, membershipService.getMembership(userId));
        return ApiResponse.success(result);
    }

    // ──────────────────────── private helpers ────────────────────────

    private void fillQuota(AiMatchResultVO result, MembershipVO membership) {
        result.setMemberLevel(membership.getLevel());
        result.setMemberLevelName(membership.getLevelName());
        result.setDailyLimit(membership.getDailyLimit());
        result.setUsedToday(membership.getUsedToday());
        result.setRemainingQuota(membership.getRemaining());
    }

    private String buildPrompt(String resume, String jd) {
        return """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(resume, jd);
    }

    private AiMatchResultVO parseResult(String rawJson) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            AiMatchResultVO vo = new AiMatchResultVO();

            vo.setScore(node.has("score") ? node.get("score").asInt(50) : 50);
            vo.setSummary(textOr(node, "summary", "AI 分析已完成"));

            vo.setStrengths(arrayToList(node.get("strengths")));
            vo.setGaps(arrayToList(node.get("gaps")));
            vo.setSuggestions(arrayToList(node.get("suggestions")));

            // 兜底：数组为空时补提示
            if (vo.getStrengths().isEmpty()) vo.getStrengths().add("未识别到明显优势，请检查简历输入");
            if (vo.getGaps().isEmpty()) vo.getGaps().add("未识别到明显差距");
            if (vo.getSuggestions().isEmpty()) vo.getSuggestions().add("建议补充更多具体经历细节");

            return vo;

        } catch (Exception e) {
            log.warn("JSON 解析失败，返回降级结果: {}", e.getMessage());
            AiMatchResultVO vo = new AiMatchResultVO();
            vo.setScore(50);
            vo.setSummary("AI 输出解析异常，已降级为基础匹配");
            vo.setStrengths(List.of("暂无法自动识别"));
            vo.setGaps(List.of("暂无法自动识别"));
            vo.setSuggestions(List.of("请尝试精简输入或稍后重试"));
            return vo;
        }
    }

    private AiMatchResultVO fallbackResult(String errMsg) {
        AiMatchResultVO vo = new AiMatchResultVO();
        vo.setScore(null);
        vo.setSummary("AI 服务暂时繁忙: " + truncate(errMsg, 80));
        vo.setStrengths(new ArrayList<>());
        vo.setGaps(new ArrayList<>(List.of("AI 调用失败，无法自动分析")));
        vo.setSuggestions(new ArrayList<>(List.of("请稍后重试，或检查本地 Ollama 是否运行")));
        return vo;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull() && !v.asText().isBlank()) ? v.asText() : fallback;
    }

    private List<String> arrayToList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String s = n.asText().trim();
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private String truncate(String s, int max) {
        return (s == null) ? "" : (s.length() > max ? s.substring(0, max) + "…" : s);
    }
}
