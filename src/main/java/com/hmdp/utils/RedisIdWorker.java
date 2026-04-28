package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// 秒杀id生成
@Component
public class RedisIdWorker {
    // 开始时间
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    // 序列号位数
    private static final long COUNT_BITS = 32;

    // 序列号需要自增长
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 业务前缀生成不同id
    public long nextId(String keyPrefix) {
        // 时间戳 31位 单位为秒
        // 需要当前时间减一个时间
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 序列号 32位
        // key不能总是同一个，否则有溢出的风险
        // 所以在key后面加一个当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long sequence = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 返回
        // 拼接需要位运算 让时间戳向左移动32位
        return timestamp << COUNT_BITS | sequence;
    }

    // 生成初始时间
    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long seconds = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(seconds);
    }
}
