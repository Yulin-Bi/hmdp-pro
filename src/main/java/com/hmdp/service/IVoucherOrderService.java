package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    // 秒杀下单
    Result seckillVoucher(Long voucherId);
    // 原创建订单
    //Result createVoucherOrder(Long voucherId);

    // 异步优化
    void creatVoucherOrder(VoucherOrder voucherOrder);
}
