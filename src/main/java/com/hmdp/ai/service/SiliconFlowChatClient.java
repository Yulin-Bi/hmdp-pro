package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.AiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SiliconFlowChatClient {
    private static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn";
    private static final String DEFAULT_MODEL = "Qwen/Qwen2.5-7B-Instruct";

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public SiliconFlowChatClient(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", resolveModel());
            payload.put("messages", messages);
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
            payload.put("temperature", 0.2);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(aiProperties.getBaseUrl()) + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("SiliconFlow调用失败: " + response.statusCode() + " - " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("SiliconFlow调用失败", e);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String resolveModel() {
        if (aiProperties.getModel() == null || aiProperties.getModel().isBlank()) {
            return DEFAULT_MODEL;
        }
        return aiProperties.getModel();
    }
}