package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryByType() {
        // 1、查询 Redis 缓存
        String typeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        // 2、缓存存在，直接返回
        if (StrUtil.isNotBlank(typeJson)) {
            return JSONUtil.toList(typeJson, ShopType.class);
        }

        // 3、缓存不存在，查询数据库
        List<ShopType> typeList = list();

        // 4、数据库不存在，返回空列表
        if (typeList == null || typeList.isEmpty()) {
            return List.of();
        }

        // 5、数据库存在，写入缓存（设置过期时间）
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_TYPE_KEY,
                JSONUtil.toJsonStr(typeList),
                CACHE_SHOP_TTL,
                java.util.concurrent.TimeUnit.MINUTES
        );

        // 6、返回结果
        return typeList;
    }
}
// ... existing code ...
