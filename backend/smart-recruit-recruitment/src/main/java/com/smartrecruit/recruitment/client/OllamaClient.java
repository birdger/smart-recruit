package com.smartrecruit.recruitment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 本地 Ollama 推理客户端。
 *
 * <p>调用 {@code POST /api/generate} 接口，使用 {@code format: json} 让模型输出可解析 JSON。
 * 完全本地运行，不依赖任何外部付费 AI API。
 */
@Component
@Slf4j
public class OllamaClient {

    private final WebClient webClient;
    private final ObjectMapper mapper;

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String baseUrl;

    @Value("${ollama.model:qwen2.5:3b}")
    private String model;

    @Value("${ollama.timeout-seconds:60}")
    private int timeoutSeconds;

    public OllamaClient() {
        this.mapper = new ObjectMapper();
        this.webClient = WebClient.builder().build();
    }

    /**
     * 同步调用 Ollama 生成（JSON 格式输出）。
     *
     * @param prompt 完整 prompt（含 JSON 输出格式指令）
     * @return 模型的 response 文本（应该是 JSON 字符串）
     */
    public String generateJson(String prompt) throws Exception {
        long start = System.currentTimeMillis();
        log.info("Ollama generate → model={}, promptLen={}", model, prompt.length());

        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false,
                    "format", "json"
            );

            String rawResponse = webClient.post()
                    .uri(baseUrl + "/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .onErrorResume(e -> Mono.error(new RuntimeException("Ollama 调用失败: " + e.getMessage(), e)))
                    .block();

            if (rawResponse == null) {
                throw new RuntimeException("Ollama 返回空响应");
            }

            JsonNode root = mapper.readTree(rawResponse);
            String text = root.path("response").asText("");

            long elapsed = System.currentTimeMillis() - start;
            log.info("Ollama generate OK → {}ms, respLen={}", elapsed, text.length());
            return text;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Ollama generate FAIL → {}ms, err={}", elapsed, e.getMessage());
            throw e;
        }
    }

    /** 获取当前配置的模型名 */
    public String getModel() {
        return model;
    }

    /** 获取推理毫秒数（供日志记录） */
    public long latency(String responseText) {
        // Ollama 返回的 total_duration 在原始 JSON 里，但已被上游解析过。
        // 这里简单返回 -1，Controller 侧会用 System.currentTimeMillis() 自己算。
        return -1;
    }
}
