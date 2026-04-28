package com.hmdp.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.AiProperties;
import com.hmdp.ai.dto.AiChatRequest;
import com.hmdp.ai.service.SiliconFlowChatClient;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiProperties aiProperties = new AiProperties();

    @Mock
    private SiliconFlowChatClient siliconFlowChatClient;

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private IShopService shopService;

    @Mock
    private QueryChainWrapper<Shop> queryChain;

    @Mock
    private CacheClient cacheClient;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private AiChatServiceImpl aiChatService;

    @BeforeEach
    void setUp() {
        aiProperties.setHistoryLimit(4);
        aiProperties.setMemoryTtlMinutes(30);
        aiChatService = new AiChatServiceImpl(siliconFlowChatClient, aiProperties, objectMapper);
        ReflectionTestUtils.setField(aiChatService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(aiChatService, "shopService", shopService);
        ReflectionTestUtils.setField(aiChatService, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(aiChatService, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(aiChatService, "rocketMQTemplate", rocketMQTemplate);
        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setNickName("tester");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void chatShouldQueryShopInfoThroughToolCall() throws Exception {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("海底捞火锅");
        shop.setArea("大关");
        shop.setAddress("上塘路458号");
        shop.setOpenHours("10:00-22:00");
        shop.setAvgPrice(104L);
        shop.setScore(49);
        shop.setComments(2764);
        when(shopService.query()).thenReturn(queryChain);
        when(queryChain.eq("name", "海底捞火锅")).thenReturn(queryChain);
        when(queryChain.orderByDesc("score")).thenReturn(queryChain);
        when(queryChain.orderByDesc("comments")).thenReturn(queryChain);
        when(queryChain.list()).thenReturn(List.of(shop));
        when(cacheClient.queryWithLogicalExpire(anyString(), any(), eq(Shop.class), any(), any(), any()))
                .thenReturn(shop);

        when(siliconFlowChatClient.chat(anyList(), anyList()))
            .thenReturn(toolCallResponse("query_shop_info", Map.of("shopName", "海底捞火锅")))
            .thenReturn(finalAnswerResponse("海底捞火锅营业到22:00。"));

        AiChatRequest request = new AiChatRequest();
        request.setMessage("帮我查一下海底捞火锅的信息");

        var response = aiChatService.chat(request);

        assertNotNull(response.getConversationId());
        assertEquals("海底捞火锅营业到22:00。", response.getAnswer());
        verify(shopService).query();
        verify(cacheClient).queryWithLogicalExpire(anyString(), any(), eq(Shop.class), any(), any(), any());
    }

    @Test
    void chatShouldCreateAppointmentThroughToolCall() throws Exception {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("海底捞火锅");
        shop.setArea("大关");
        shop.setAddress("上塘路458号");
        when(cacheClient.queryWithLogicalExpire(anyString(), any(), eq(Shop.class), any(), any(), any()))
                .thenReturn(shop);
        when(redisIdWorker.nextId("appointment")).thenReturn(99L);
        doAnswer(invocation -> null).when(rocketMQTemplate).syncSend(anyString(), any(Message.class));

        when(siliconFlowChatClient.chat(anyList(), anyList()))
            .thenReturn(toolCallResponse("book_shop_visit", Map.of(
                "shopId", 1,
                "visitTime", "2026-04-30 18:00",
                "contactPhone", "13800000000",
                "remark", "两人到店")))
            .thenReturn(finalAnswerResponse("已经帮你预约成功。"));

        AiChatRequest request = new AiChatRequest();
        request.setMessage("帮我预约明天晚上 6 点到店");
        request.setShopId(1L);

        var response = aiChatService.chat(request);

        assertNotNull(response.getConversationId());
        assertEquals(Long.valueOf(99L), response.getAppointmentId());
        assertEquals(Long.valueOf(1L), response.getShopId());
        assertEquals("已经帮你预约成功。", response.getAnswer());
        verify(rocketMQTemplate).syncSend(anyString(), any(Message.class));
    }

    @Test
    void chatShouldHandleRecommendationLocallyWithoutModelCall() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("热辣火锅");
        shop.setArea("大关");
        shop.setAvgPrice(88L);
        shop.setScore(48);
        shop.setSold(1200);
        shop.setComments(320);

        when(shopService.query()).thenReturn(queryChain);
        when(queryChain.orderByDesc("score")).thenReturn(queryChain);
        when(queryChain.orderByDesc("sold")).thenReturn(queryChain);
        when(queryChain.orderByDesc("comments")).thenReturn(queryChain);
        Page<Shop> page = new Page<>(1, 5);
        page.setRecords(List.of(shop));
        when(queryChain.page(any())).thenReturn(page);

        AiChatRequest request = new AiChatRequest();
        request.setMessage("附近的店有什么推荐的吗");

        var response = aiChatService.chat(request);

        assertNotNull(response.getConversationId());
        assertEquals("recommend_shops", response.getToolName());
        assertTrue(response.getAnswer().contains("热辣火锅"));
        verify(siliconFlowChatClient, org.mockito.Mockito.never()).chat(anyList(), anyList());
    }

    private com.fasterxml.jackson.databind.JsonNode toolCallResponse(String functionName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call_1");
        toolCall.put("type", "function");

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", functionName);
        function.put("arguments", objectMapper.writeValueAsString(arguments));
        toolCall.put("function", function);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("tool_calls", List.of(toolCall));

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("choices", List.of(choice));
        return objectMapper.valueToTree(response);
    }

    private com.fasterxml.jackson.databind.JsonNode finalAnswerResponse(String answer) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", answer);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("choices", List.of(choice));
        return objectMapper.valueToTree(response);
    }
}