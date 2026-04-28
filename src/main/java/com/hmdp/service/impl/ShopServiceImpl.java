package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jodd.util.StringUtil;
import org.redisson.client.protocol.RedisCommands;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.security.Key;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    // 注入redis
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 注入缓存管理工具类
    @Resource
    private CacheClient cacheClient;

    // 分类查询店铺并按距离升序排序
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1、判断是否需要按距离查询
        if (x == null || y == null) {
            // 若没传坐标则返回所有
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 2、计算分页查询
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int to = current + SystemConstants.DEFAULT_PAGE_SIZE;
        // 3、查询缓存，按照距离排序：结果有shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(to));
        // 4、根据id查询返回
        if (results == null) {
            return Result.fail("店铺不存在");
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 无数据，返回空集合
            return Result.ok(Collections.emptyList());
        }
        // 5、截取from -  to
        List<Long> ids = new ArrayList<>( list.size());
        Map<String, Distance> distanceMap = new HashMap<>( list.size());
        list.stream().skip( from).forEach(result -> {
           // 获取shopId
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        // 6、根据id查询数据库
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    // 根据id查询商铺
    @Override
    public Result queryById(Long id) throws InterruptedException {
        // 1、缓存null值解决缓存穿透
        //Shop shop = queryWithPassThrough( id);
        // 利用工具类
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 2、互斥锁方式
        //Shop shop = queryWithPassMutex(id);
        // 3、逻辑过期方式
        //Shop shop = queryWithLogicalExpire(id);
        // 利用工具类
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null){
            return Result.fail("店铺不存在");
        }
        // 7、返回
        return Result.ok(shop);
    }

    // 定义一把互斥锁 在redis里面
    // 尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 缓存穿透解决封装
    public Shop queryWithPassThrough(Long id) {
        System.out.println("=== 开始查询店铺，id=" + id);

        // 1、从 redis 中查询缓存 并反序列化为对象
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        System.out.println("=== Redis 查询结果：" + (shopJson == null ? "缓存不存在" : "缓存存在"));

        // 2、判断缓存是否存在
        // 3、存在缓存，直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断命中的是否为 空
        if ("".equals(shopJson)){
            return null;
        }
        // 4、不存在缓存，查询数据库
        Shop shop = getById(id);
        System.out.println("=== 数据库查询结果：" + (shop == null ? "店铺不存在" : "店铺存在，name=" + shop.getName()));

        // 5、不存在，返回错误
        // 更改：将空值写入缓存，设置有效期，避免缓存穿透
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6、存在，写入缓存 并设置超时时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        System.out.println("=== 写入缓存：" + CACHE_SHOP_KEY + id);

        // 7、返回
        return shop;
    }

    // 缓存击穿解决封装
    public Shop queryWithPassMutex(Long id) throws InterruptedException {
        System.out.println("=== 开始查询店铺，id=" + id);

        // 1、从 redis 中查询缓存 并反序列化为对象
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        System.out.println("=== Redis 查询结果：" + (shopJson == null ? "缓存不存在" : "缓存存在"));

        // 2、判断缓存是否存在
        // 3、存在缓存，直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断命中的是否为 空
        if ("".equals(shopJson)){
            return null;
        }

        Shop shop = null;
        try {
            // 缓存击穿补充
            // 1、未命中获取互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            // 2、判断锁获取是否成功
            // 3、获取失败 休眠
            if (!isLock) {
                Thread.sleep(50);
                return queryWithPassMutex(id);
            }
            // 4、获取成功 查询数据库
            //  且不存在缓存，查询数据库
            shop = getById(id);
            System.out.println("=== 数据库查询结果：" + (shop == null ? "店铺不存在" : "店铺存在，name=" + shop.getName()));

            // 模拟延迟
            Thread.sleep(200);

            // 5、不存在，返回错误
            // 更改：将空值写入缓存，设置有效期，避免缓存穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5、将数据写入缓存
            // 并设置超时时间
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            System.out.println("=== 写入缓存：" + CACHE_SHOP_KEY + id);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 无论何种情况，都释放锁
            unLock(LOCK_SHOP_KEY + id);
        }

        // 6、返回
        return shop;
    }

    // 将热点商铺信息缓存到redis里
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1、查询店铺数据
        Shop shop = getById(id);
        // 增加延迟模拟查询
        Thread.sleep(200);
        // 2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3、写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    // 通过逻辑过期解决
    // 创建一个线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id) {
        System.out.println("=== 开始查询店铺，id=" + id);

        // 1、从 redis 中查询缓存 并反序列化为对象
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        System.out.println("=== Redis 查询结果：" + (shopJson == null ? "缓存不存在" : "缓存存在"));

        // 2、判断缓存是否存在
        // 3、不存在直接返回空
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 4、存在缓存，判断是否过期
        // 需要先把对象Json反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 4.1 未过期 直接返回缓存数据
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期 直接返回缓存数据
            return shop;
        }
        // 4.2 过期 需要缓存重建
        // 5、缓存重建
        // 5.1 获取锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        // 5.2 判断锁是否获取成功
        if (isLock) {
            // 5.3 成功 开启独立线程进行缓存重建【线程池】
            // 5.4 释放锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 创建一个线程，查询数据库
                    this.saveShop2Redis(id, 20L);
                    System.out.println("=== 缓存重建完成，id=" + id);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }

        // 6、返回旧的shop
        return shop;
    }

    // 更新商铺信息
    // 注意增删改查要放事物里
    @Transactional
    @Override
    public Result upDate(Shop shop) {
        // 判断id是否存在
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不存在");
        }
        // 1、更新数据库
        updateById(shop);
        // 2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }


}
