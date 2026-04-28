package com.hmdp.ai.controller;

import com.hmdp.ai.dto.AiChatRequest;
import com.hmdp.ai.service.IAiChatService;
import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiChatController {

    @Resource(name = "aiChatServiceImpl")
    private IAiChatService aiChatService;

    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatRequest request) {
        return Result.ok(aiChatService.chat(request));
    }
}