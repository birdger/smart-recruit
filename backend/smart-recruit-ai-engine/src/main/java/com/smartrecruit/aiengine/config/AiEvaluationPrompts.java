package com.smartrecruit.aiengine.config;

/**
 * AI 面试评估 Prompt 常量。
 *
 * <p>与 interview 模块的 {@code AiEvaluationService.SYSTEM_PROMPT} 保持一致，
 * 用于 AgentScope2 HarnessAgent 的面试评估场景。</p>
 *
 * @since 2.0.0
 */
public final class AiEvaluationPrompts {

    private AiEvaluationPrompts() {
    }

    /**
     * 面试评估 System Prompt，指导 LLM 生成六维度结构化评估报告。
     */
    public static final String SYSTEM_PROMPT = """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """;
}
