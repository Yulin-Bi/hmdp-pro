package com.hmdp.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.siliconflow")
public class AiProperties {
    private String baseUrl;
    private String apiKey;
    private String model;
    private Integer timeoutSeconds = 180;
    private Integer memoryTtlMinutes = 120;
    private Integer historyLimit = 12;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getMemoryTtlMinutes() {
        return memoryTtlMinutes;
    }

    public void setMemoryTtlMinutes(Integer memoryTtlMinutes) {
        this.memoryTtlMinutes = memoryTtlMinutes;
    }

    public Integer getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(Integer historyLimit) {
        this.historyLimit = historyLimit;
    }
}