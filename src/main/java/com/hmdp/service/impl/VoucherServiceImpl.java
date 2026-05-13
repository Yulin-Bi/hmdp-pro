package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存库存到redis中
        // 无需设置有效期
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }

    // 每次重启服务把秒杀库存和普通券库存加载到redis中
    @PostConstruct
    private void loadSeckillStocks() {
        // 1. 加载秒杀券库存
        List<SeckillVoucher> seckillVouchers = seckillVoucherService.list();
        for (SeckillVoucher sv : seckillVouchers) {
            if (sv.getEndTime().isAfter(LocalDateTime.now()) && sv.getStock() > 0) {
                String key = SECKILL_STOCK_KEY + sv.getVoucherId();
                Boolean hasKey = stringRedisTemplate.hasKey(key);
                if (!hasKey) {
                    stringRedisTemplate.opsForValue().set(key, sv.getStock().toString());
                }
            }
        }
        // 2. 为普通券（无秒杀记录）设置默认库存
        List<Voucher> regularVouchers = getBaseMapper().selectList(null);
        for (Voucher v : regularVouchers) {
            String key = SECKILL_STOCK_KEY + v.getId();
            Boolean hasKey = stringRedisTemplate.hasKey(key);
            if (!hasKey && v.getStatus() == 1) {
                stringRedisTemplate.opsForValue().set(key, "9999");
            }
        }
    }

}
