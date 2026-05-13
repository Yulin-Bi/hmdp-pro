package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.RewardRecord;
import com.hmdp.entity.RewardRule;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.RewardRecordMapper;
import com.hmdp.mapper.RewardRuleMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IRewardService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class RewardServiceImpl implements IRewardService {

    @Resource
    private RewardRuleMapper rewardRuleMapper;

    @Resource
    private RewardRecordMapper rewardRecordMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    @Transactional
    public void processReward(Long userId, String rewardType, String targetId) {
        // 1. 幂等检查
        QueryWrapper<RewardRecord> recordWrapper = new QueryWrapper<>();
        recordWrapper.eq("user_id", userId)
                .eq("reward_type", rewardType)
                .eq("target_id", targetId);
        Integer count = rewardRecordMapper.selectCount(recordWrapper);
        if (count > 0) {
            log.warn("奖励已发放，userId:{}, rewardType:{}, targetId:{}", userId, rewardType, targetId);
            return;
        }

        // 2. 匹配启用的奖励规则
        QueryWrapper<RewardRule> ruleWrapper = new QueryWrapper<>();
        ruleWrapper.eq("reward_type", rewardType)
                .eq("status", 1);
        RewardRule rule = rewardRuleMapper.selectOne(ruleWrapper);
        if (rule == null) {
            log.warn("无匹配的奖励规则，rewardType:{}", rewardType);
            return;
        }

        // 3. 校验券库存
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(rule.getVoucherId());
        if (seckillVoucher == null || seckillVoucher.getStock() == null || seckillVoucher.getStock() <= 0) {
            log.warn("奖励券库存不足，voucherId:{}", rule.getVoucherId());
            return;
        }

        // 4. 生成订单 ID
        long orderId = redisIdWorker.nextId("order");

        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", rule.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("扣减库存失败，voucherId:{}", rule.getVoucherId());
            return;
        }

        // 6. 创建订单
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(rule.getVoucherId());
        order.setStatus(1);
        order.setCreateTime(LocalDateTime.now());
        voucherOrderMapper.insert(order);

        // 7. 写入奖励发放记录（唯一索引保证幂等）
        RewardRecord record = new RewardRecord();
        record.setUserId(userId);
        record.setRewardType(rewardType);
        record.setTargetId(targetId);
        record.setVoucherId(rule.getVoucherId());
        record.setVoucherOrderId(orderId);
        record.setCreateTime(LocalDateTime.now());
        rewardRecordMapper.insert(record);

        log.info("奖励发放成功，userId:{}, rewardType:{}, targetId:{}, voucherId:{}, orderId:{}",
                userId, rewardType, targetId, rule.getVoucherId(), orderId);
    }
}
