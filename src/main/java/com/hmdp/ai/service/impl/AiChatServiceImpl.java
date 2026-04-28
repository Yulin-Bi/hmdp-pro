package com.hmdp.ai.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.AiProperties;
import com.hmdp.ai.dto.AiChatRequest;
import com.hmdp.ai.dto.AiChatResponse;
import com.hmdp.ai.entity.ShopAppointmentMessage;
import com.hmdp.ai.service.IAiChatService;
import com.hmdp.ai.service.SiliconFlowChatClient;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Service
public class AiChatServiceImpl implements IAiChatService {

    private static final String HISTORY_KEY_PREFIX = "ai:chat:history:";
    private static final String META_KEY_PREFIX = "ai:chat:meta:";
    private static final String SHOP_APPOINTMENT_TOPIC = "shop-appointment-topic";

    private final SiliconFlowChatClient siliconFlowChatClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "shopServiceImpl")
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    public AiChatServiceImpl(SiliconFlowChatClient siliconFlowChatClient,
                             AiProperties aiProperties,
                             ObjectMapper objectMapper) {
        this.siliconFlowChatClient = siliconFlowChatClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiChatResponse chat(AiChatRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new IllegalStateException("请先登录");
        }
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            throw new IllegalArgumentException("消息不能为空");
        }

        String conversationId = resolveConversationId(user.getId(), request.getConversationId());
        Long contextShopId = resolveContextShopId(user.getId(), request.getShopId());

        if (shouldHandleRecommendationLocally(request.getMessage())) {
            AiChatResponse result = handleLocalRecommendation(conversationId, contextShopId, request.getMessage());
            saveTurn(user.getId(), request.getMessage(), result.getAnswer(), result.getShopId(), conversationId);
            return result;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage(contextShopId));
        messages.addAll(loadHistory(user.getId()));
        messages.add(userMessage(request.getMessage(), contextShopId));

        JsonNode response = siliconFlowChatClient.chat(messages, toolDefinitions());
        JsonNode assistantMessage = response.path("choices").path(0).path("message");
        AiChatResponse result = handleAssistantMessage(user.getId(), conversationId, contextShopId, messages, assistantMessage);
        saveTurn(user.getId(), request.getMessage(), result.getAnswer(), result.getShopId(), conversationId);
        return result;
    }

    private AiChatResponse handleAssistantMessage(Long userId,
                                                   String conversationId,
                                                   Long contextShopId,
                                                   List<Map<String, Object>> messages,
                                                   JsonNode assistantMessage) {
        if (assistantMessage.has("tool_calls") && assistantMessage.get("tool_calls").isArray() && !assistantMessage.get("tool_calls").isEmpty()) {
            Map<String, Object> assistantMessageMap = objectMapper.convertValue(assistantMessage, new TypeReference<>() {});
            messages.add(assistantMessageMap);
            Long usedShopId = contextShopId;
            Long appointmentId = null;
            String toolName = null;
            for (JsonNode toolCall : assistantMessage.get("tool_calls")) {
                String functionName = toolCall.path("function").path("name").asText();
                String arguments = toolCall.path("function").path("arguments").asText();
                toolName = functionName;
                String toolResult = executeTool(userId, functionName, arguments, contextShopId);
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCall.path("id").asText());
                toolMessage.put("content", toolResult);
                messages.add(toolMessage);
                if (functionName.startsWith("book")) {
                    JsonNode parsed = safeParse(toolResult);
                    if (parsed.hasNonNull("appointmentId")) {
                        appointmentId = parsed.path("appointmentId").asLong();
                    }
                    if (parsed.hasNonNull("shopId")) {
                        usedShopId = parsed.path("shopId").asLong();
                    }
                }
            }
            JsonNode followUp = siliconFlowChatClient.chat(messages, toolDefinitions());
            String answer = followUp.path("choices").path(0).path("message").path("content").asText("我已经帮你处理好了。");
            AiChatResponse response = new AiChatResponse();
            response.setConversationId(conversationId);
            response.setAnswer(answer);
            response.setShopId(usedShopId);
            response.setAppointmentId(appointmentId);
            response.setToolName(toolName);
            return response;
        }

        String answer = assistantMessage.path("content").asText("抱歉，我暂时没有生成可用回复。");
        AiChatResponse response = new AiChatResponse();
        response.setConversationId(conversationId);
        response.setAnswer(answer);
        response.setShopId(contextShopId);
        return response;
    }

    private String executeTool(Long userId, String functionName, String arguments, Long fallbackShopId) {
        JsonNode parsedArgs = safeParse(arguments);
        Map<String, Object> args = objectMapper.convertValue(parsedArgs, new TypeReference<>() {});
        if ("query_shop_info".equals(functionName)) {
            return queryShopInfo(args, fallbackShopId);
        }
        if ("recommend_shops".equals(functionName)) {
            return recommendShops(args, fallbackShopId);
        }
        if ("book_shop_visit".equals(functionName)) {
            return bookShopVisit(userId, args, fallbackShopId);
        }
        return JSONUtil.createObj().set("error", "未知工具").toString();
    }

    private String queryShopInfo(Map<String, Object> args, Long fallbackShopId) {
        Long shopId = toLong(args.get("shopId"));
        if (shopId == null) {
            shopId = fallbackShopId;
        }
        String shopName = firstNotBlank(asString(args.get("shopName")), asString(args.get("keyword")), asString(args.get("name")));
        if (shopId == null && StrUtil.isNotBlank(shopName)) {
            List<Shop> candidates = searchShopCandidates(shopName);
            if (candidates.isEmpty()) {
                return JSONUtil.createObj().set("message", "未找到匹配的店铺信息").toString();
            }
            if (candidates.size() > 1) {
                return buildCandidateResponse("找到多个匹配店铺，请补充店铺名或区域", candidates);
            }
            shopId = candidates.get(0).getId();
        }
        if (shopId == null) {
            return JSONUtil.createObj().set("message", "请提供店铺名或店铺id").toString();
        }

        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, shopId, Shop.class, shopService::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return JSONUtil.createObj().set("message", "未找到对应的店铺信息").toString();
        }
        return JSONUtil.createObj()
                .set("message", "已找到店铺信息")
                .set("shopId", shop.getId())
                .set("shopName", shop.getName())
                .set("matchedShop", shopDetailMap(shop))
                .toString();
    }

    private String bookShopVisit(Long userId, Map<String, Object> args, Long fallbackShopId) {
        Long shopId = toLong(args.get("shopId"));
        if (shopId == null) {
            shopId = fallbackShopId;
        }
        String shopName = firstNotBlank(asString(args.get("shopName")), asString(args.get("keyword")), asString(args.get("name")));
        if (shopId == null && StrUtil.isNotBlank(shopName)) {
            List<Shop> candidates = searchShopCandidates(shopName);
            if (candidates.isEmpty()) {
                return JSONUtil.createObj().set("message", "未找到可预约的店铺").toString();
            }
            if (candidates.size() > 1) {
                return buildCandidateResponse("找到多个可预约店铺，请先确认具体门店", candidates);
            }
            shopId = candidates.get(0).getId();
        }
        if (shopId == null) {
            return JSONUtil.createObj().set("error", "请先提供要预约的店铺").toString();
        }
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, shopId, Shop.class, shopService::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return JSONUtil.createObj().set("error", "店铺不存在").toString();
        }

        String visitTimeText = asString(args.get("visitTime"));
        String contactPhone = asString(args.get("contactPhone"));
        String remark = asString(args.get("remark"));
        if (StrUtil.isBlank(visitTimeText)) {
            return JSONUtil.createObj().set("error", "请提供预约到店时间，格式例如 2026-04-30 18:00").toString();
        }
        if (StrUtil.isBlank(contactPhone)) {
            return JSONUtil.createObj().set("error", "请提供联系电话，便于店铺确认预约").toString();
        }

        LocalDateTime visitTime = parseVisitTime(visitTimeText);
        if (visitTime == null) {
            return JSONUtil.createObj().set("error", "预约时间格式不正确").toString();
        }

        long appointmentId = redisIdWorker.nextId("appointment");
        ShopAppointmentMessage message = new ShopAppointmentMessage(
                appointmentId,
                userId,
                shop.getId(),
                shop.getName(),
                visitTime.toString(),
                contactPhone,
                remark
        );
        Message<ShopAppointmentMessage> mqMessage = MessageBuilder.withPayload(message).build();
        rocketMQTemplate.syncSend(SHOP_APPOINTMENT_TOPIC, mqMessage);

        return JSONUtil.createObj()
                .set("appointmentId", appointmentId)
                .set("shopId", shop.getId())
                .set("shopName", shop.getName())
                .set("visitTime", visitTime.toString())
                .set("message", "预约已受理，正在排队创建").toString();
    }

    private String recommendShops(Map<String, Object> args, Long fallbackShopId) {
        Long typeId = toLong(args.get("typeId"));
        String keyword = firstNotBlank(asString(args.get("keyword")), asString(args.get("shopName")), asString(args.get("area")));
        Double x = toDouble(args.get("x"));
        Double y = toDouble(args.get("y"));
        Integer current = toInteger(args.get("current"));
        Integer count = toInteger(args.get("count"));

        int pageNo = current == null || current < 1 ? 1 : current;
        int limit = count == null || count < 1 ? 5 : Math.min(count, 10);

        if (typeId == null && fallbackShopId != null && StrUtil.isBlank(keyword)) {
            Shop currentShop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, fallbackShopId, Shop.class, shopService::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            if (currentShop != null && currentShop.getTypeId() != null) {
                typeId = currentShop.getTypeId();
            }
        }

        List<Shop> shops;
        if (typeId != null && x != null && y != null) {
            Result result = shopService.queryShopByType(typeId.intValue(), pageNo, x, y);
            shops = extractShops(result == null ? null : result.getData());
        } else {
            var query = shopService.query();
            if (typeId != null) {
                query.eq("type_id", typeId);
            }
            if (StrUtil.isNotBlank(keyword)) {
                query.and(wrapper -> wrapper.like("name", keyword).or().like("area", keyword));
            }
            shops = query.orderByDesc("score")
                    .orderByDesc("sold")
                    .orderByDesc("comments")
                    .page(new Page<>(pageNo, limit))
                    .getRecords();
        }

        if (CollUtil.isEmpty(shops)) {
            return JSONUtil.createObj().set("message", "暂时没有找到适合推荐的店铺").toString();
        }

        List<Map<String, Object>> list = shops.stream()
                .limit(limit)
                .map(this::shopSummaryMap)
                .collect(Collectors.toList());
        return JSONUtil.createObj()
                .set("message", "推荐结果")
                .set("shops", list)
                .toString();
    }

    private AiChatResponse handleLocalRecommendation(String conversationId, Long contextShopId, String message) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("count", 5);
        String payload = recommendShops(args, contextShopId);
        String answer = formatRecommendationAnswer(payload, message);

        AiChatResponse response = new AiChatResponse();
        response.setConversationId(conversationId);
        response.setAnswer(answer);
        response.setShopId(contextShopId);
        response.setToolName("recommend_shops");
        return response;
    }

    private String formatRecommendationAnswer(String payload, String message) {
        JsonNode data = safeParse(payload);
        String title = data.path("message").asText("我先给你推荐几家店：");
        JsonNode shops = data.path("shops");
        if (!shops.isArray() || shops.isEmpty()) {
            return title + "\n暂时没有找到合适的店铺。";
        }

        StringBuilder answer = new StringBuilder(title).append("\n");
        int limit = Math.min(shops.size(), 5);
        for (int i = 0; i < limit; i++) {
            JsonNode shop = shops.get(i);
            answer.append(i + 1)
                    .append(". ")
                    .append(shop.path("name").asText("未知店铺"));
            String area = shop.path("area").asText("");
            String avgPrice = shop.path("avgPrice").isMissingNode() || shop.path("avgPrice").isNull()
                    ? ""
                    : shop.path("avgPrice").asText() + "元";
            String score = shop.path("score").isMissingNode() || shop.path("score").isNull()
                    ? ""
                    : "评分" + shop.path("score").asText();
            String distance = shop.path("distance").isMissingNode() || shop.path("distance").isNull()
                    ? ""
                    : shop.path("distance").asText() + "m";
            List<String> parts = new ArrayList<>();
            if (StrUtil.isNotBlank(area)) {
                parts.add(area);
            }
            if (StrUtil.isNotBlank(avgPrice)) {
                parts.add(avgPrice);
            }
            if (StrUtil.isNotBlank(score)) {
                parts.add(score);
            }
            if (StrUtil.isNotBlank(distance)) {
                parts.add(distance);
            }
            if (!parts.isEmpty()) {
                answer.append("（").append(String.join("，", parts)).append("）");
            }
            answer.append("\n");
        }
        answer.append("如果你想按商圈、品类或者价格继续筛选，可以继续告诉我。\n");
        if (StrUtil.isNotBlank(message) && message.contains("附近")) {
            answer.append("你这次问的是附近推荐，我先按热门店铺给你看。\n");
        }
        return answer.toString().trim();
    }

    private boolean shouldHandleRecommendationLocally(String message) {
        if (StrUtil.isBlank(message)) {
            return false;
        }
        String text = message.trim();
        boolean recommendIntent = text.contains("推荐") || text.contains("附近") || text.contains("有什么店") || text.contains("有哪些店") || text.contains("哪家店") || text.contains("找店");
        if (!recommendIntent) {
            return false;
        }
        return !(text.contains("预约") || text.contains("查询") || text.contains("查一下") || text.contains("营业") || text.contains("价格") || text.contains("电话"));
    }

    private List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(toolDefinition("query_shop_info",
                "查询店铺信息，适用于店铺营业时间、地址、均价、评分等咨询；支持 shopId、shopName 或关键词",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "shopId", Map.of("type", "integer", "description", "店铺id"),
                                "shopName", Map.of("type", "string", "description", "店铺名称"),
                                "keyword", Map.of("type", "string", "description", "店铺名称或关键词")
                        )
                )));
        tools.add(toolDefinition("recommend_shops",
                "推荐店铺，适用于热门店铺、附近店铺或同类店铺推荐",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "typeId", Map.of("type", "integer", "description", "店铺类型id"),
                                "keyword", Map.of("type", "string", "description", "店铺名称、商圈或关键词"),
                                "area", Map.of("type", "string", "description", "商圈名称"),
                                "x", Map.of("type", "number", "description", "经度"),
                                "y", Map.of("type", "number", "description", "纬度"),
                                "current", Map.of("type", "integer", "description", "分页页码"),
                                "count", Map.of("type", "integer", "description", "推荐数量")
                        )
                )));
        tools.add(toolDefinition("book_shop_visit",
                "预约用户到店，适用于确认到店时间和联系方式后创建预约；消息队列异步落库",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "shopId", Map.of("type", "integer", "description", "店铺id"),
                                "shopName", Map.of("type", "string", "description", "店铺名称"),
                                "visitTime", Map.of("type", "string", "description", "预约到店时间，格式例如 2026-04-30 18:00"),
                                "contactPhone", Map.of("type", "string", "description", "联系电话"),
                                "remark", Map.of("type", "string", "description", "备注")
                        ),
                        "required", List.of("visitTime", "contactPhone")
                )));
        return tools;
    }

    private Map<String, Object> toolDefinition(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private Map<String, Object> systemMessage(Long shopId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是黑马点评的智能客服，优先用简洁、自然的中文回答。\\n");
        prompt.append("当用户想查询店铺信息时，调用 query_shop_info 工具；如果用户只说店铺名，也可以直接传 shopName。\n");
        prompt.append("当用户想获得店铺推荐时，调用 recommend_shops 工具。\n");
        prompt.append("当用户想预约到店时，调用 book_shop_visit 工具；如果缺少预约时间或联系电话，先追问补齐。\\n");
        prompt.append("预约结果由消息队列异步创建，先告诉用户已受理。\n");
        if (shopId != null) {
            prompt.append("当前页面店铺id：").append(shopId).append("，如果用户没有明确指定店铺，优先按当前店铺理解。\\n");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", prompt.toString());
        return message;
    }

    private List<Shop> searchShopCandidates(String shopName) {
        if (StrUtil.isBlank(shopName)) {
            return Collections.emptyList();
        }
        List<Shop> exact = shopService.query()
                .eq("name", shopName)
                .orderByDesc("score")
                .orderByDesc("comments")
                .list();
        if (CollUtil.isNotEmpty(exact)) {
            return exact;
        }
        return shopService.query()
                .like("name", shopName)
                .or()
                .like("area", shopName)
                .orderByDesc("score")
                .orderByDesc("comments")
                .list();
    }

    private String buildCandidateResponse(String message, List<Shop> candidates) {
        return JSONUtil.createObj()
                .set("message", message)
                .set("candidates", candidates.stream().map(this::shopSummaryMap).collect(Collectors.toList()))
                .toString();
    }

    private Map<String, Object> shopDetailMap(Shop shop) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shopId", shop.getId());
        item.put("name", shop.getName());
        item.put("typeId", shop.getTypeId());
        item.put("area", shop.getArea());
        item.put("address", shop.getAddress());
        item.put("openHours", shop.getOpenHours());
        item.put("avgPrice", shop.getAvgPrice());
        item.put("sold", shop.getSold());
        item.put("comments", shop.getComments());
        item.put("score", shop.getScore());
        item.put("images", shop.getImages());
        item.put("distance", shop.getDistance());
        return item;
    }

    private Map<String, Object> shopSummaryMap(Shop shop) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shopId", shop.getId());
        item.put("name", shop.getName());
        item.put("typeId", shop.getTypeId());
        item.put("area", shop.getArea());
        item.put("avgPrice", shop.getAvgPrice());
        item.put("sold", shop.getSold());
        item.put("comments", shop.getComments());
        item.put("score", shop.getScore());
        item.put("openHours", shop.getOpenHours());
        item.put("distance", shop.getDistance());
        return item;
    }

    private List<Shop> extractShops(Object data) {
        if (data == null) {
            return new ArrayList<>();
        }
        if (data instanceof List<?>) {
            List<Shop> shops = new ArrayList<>();
            for (Object item : (List<?>) data) {
                shops.add(item instanceof Shop ? (Shop) item : objectMapper.convertValue(item, Shop.class));
            }
            return shops;
        }
        return new ArrayList<>(Collections.singletonList(objectMapper.convertValue(data, Shop.class)));
    }

    private String firstNotBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> userMessage(String message, Long shopId) {
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", shopId == null ? message : "当前店铺id=" + shopId + "。用户消息：" + message);
        return userMessage;
    }

    private List<Map<String, Object>> loadHistory(Long userId) {
        List<String> raw = stringRedisTemplate.opsForList().range(historyKey(userId), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (String item : raw) {
            try {
                history.add(objectMapper.readValue(item, new TypeReference<>() {}));
            } catch (Exception ignored) {
            }
        }
        return history;
    }

    private void saveTurn(Long userId, String userMessage, String assistantAnswer, Long shopId, String conversationId) {
        Map<String, Object> userRecord = new LinkedHashMap<>();
        userRecord.put("role", "user");
        userRecord.put("content", userMessage);
        Map<String, Object> assistantRecord = new LinkedHashMap<>();
        assistantRecord.put("role", "assistant");
        assistantRecord.put("content", assistantAnswer);
        String historyKey = historyKey(userId);
        stringRedisTemplate.opsForList().rightPush(historyKey, JSONUtil.toJsonStr(userRecord));
        stringRedisTemplate.opsForList().rightPush(historyKey, JSONUtil.toJsonStr(assistantRecord));
        stringRedisTemplate.opsForList().trim(historyKey, -aiProperties.getHistoryLimit(), -1);
        stringRedisTemplate.expire(historyKey, aiProperties.getMemoryTtlMinutes(), TimeUnit.MINUTES);
        if (shopId != null) {
            stringRedisTemplate.opsForHash().put(metaKey(userId), "shopId", String.valueOf(shopId));
        }
        stringRedisTemplate.opsForHash().put(metaKey(userId), "conversationId", conversationId);
        stringRedisTemplate.expire(metaKey(userId), aiProperties.getMemoryTtlMinutes(), TimeUnit.MINUTES);
    }

    private String resolveConversationId(Long userId, String conversationId) {
        String key = metaKey(userId);
        if (StrUtil.isNotBlank(conversationId)) {
            stringRedisTemplate.opsForHash().put(key, "conversationId", conversationId);
            stringRedisTemplate.expire(key, aiProperties.getMemoryTtlMinutes(), TimeUnit.MINUTES);
            return conversationId;
        }
        Object cached = stringRedisTemplate.opsForHash().get(key, "conversationId");
        if (cached != null && StrUtil.isNotBlank(cached.toString())) {
            return cached.toString();
        }
        String generated = UUID.randomUUID().toString();
        stringRedisTemplate.opsForHash().put(key, "conversationId", generated);
        stringRedisTemplate.expire(key, aiProperties.getMemoryTtlMinutes(), TimeUnit.MINUTES);
        return generated;
    }

    private Long resolveContextShopId(Long userId, Long requestShopId) {
        if (requestShopId != null) {
            stringRedisTemplate.opsForHash().put(metaKey(userId), "shopId", String.valueOf(requestShopId));
            return requestShopId;
        }
        Object cached = stringRedisTemplate.opsForHash().get(metaKey(userId), "shopId");
        return cached == null ? null : toLong(cached);
    }

    private String historyKey(Long userId) {
        return HISTORY_KEY_PREFIX + userId;
    }

    private String metaKey(Long userId) {
        return META_KEY_PREFIX + userId;
    }

    private JsonNode safeParse(String value) {
        try {
            if (StrUtil.isBlank(value)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(value);
        } catch (java.io.IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime parseVisitTime(String visitTimeText) {
        List<DateTimeFormatter> formatters = Arrays.asList(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(visitTimeText, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}