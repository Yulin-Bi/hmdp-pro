package com.hmdp;


import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.api.async.RedisGeoAsyncCommands;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.springframework.data.geo.Point;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 测试id生成 单元测试500线程池
    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService executorService = new ThreadPoolExecutor(
            500,
            1000,
            10,
            TimeUnit.SECONDS,
            new LinkedBlockingDeque<>(100000),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.DiscardPolicy()
    );


    // 测试缓存击穿
    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    // 测试id生成
    @Test
    void testIdWorker() {

        for (int i = 0; i < 1000; i++) {
            long id = redisIdWorker.nextId("order");
            System.out.println("id = " + id);
        }

    }

    // 测试店铺信息查询
    @Test
    void loadShopData() throws InterruptedException {
        // 查询店铺
        List<Shop> list = shopService.list();
        // 店铺分组 typeId
        Map<Long, List<Shop>> map = list.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));
        // 分批写入reids
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取key
            Long typeId = entry.getKey();
            // 获取同类型店铺
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>( value.size());
            // 把经纬度写进redis
            for (Shop shop : value){
//                stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + typeId,
//                        new Point(shop.getX(), shop.getY()),
//                        shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + typeId, locations);
        }
    }

    // 测试UV统计内存占用
    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
}
