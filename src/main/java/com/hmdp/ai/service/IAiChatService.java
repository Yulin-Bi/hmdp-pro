package com.hmdp.ai.service;

import com.hmdp.ai.dto.AiChatRequest;
import com.hmdp.ai.dto.AiChatResponse;

public interface IAiChatService {
    AiChatResponse chat(AiChatRequest request);
}