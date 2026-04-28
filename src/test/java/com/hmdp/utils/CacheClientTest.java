package com.hmdp.utils;

import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void queryWithLogicalExpireShouldRebuildWhenExpireTimeMissing() {
        CacheClient cacheClient = new CacheClient(stringRedisTemplate);
        String key = RedisConstants.CACHE_SHOP_KEY + 1L;
        String malformedValue = "{\"data\":{\"id\":1,\"name\":\"test shop\"}}";
        when(stringRedisTemplate.opsForValue().get(key)).thenReturn(malformedValue);

        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("test shop");

        Shop result = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY,
                1L,
                Shop.class,
                ignored -> shop,
                10L,
                TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("test shop", result.getName());
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stringRedisTemplate.opsForValue()).set(eq(key), valueCaptor.capture());
        assertNotNull(valueCaptor.getValue());
    }
}