package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {
    // 根据id查询商品
    Result queryById(Long id) throws InterruptedException;

    // 更新店铺
    Result upDate(Shop shop);

    // 店铺分类查询
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
