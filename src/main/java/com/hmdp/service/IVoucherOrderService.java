package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderDTO;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void creatVoucherOrder(VoucherOrder voucherOrder);

    List<VoucherOrderDTO> queryMyVouchers(Long userId);
}
