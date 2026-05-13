package com.hmdp.service;

public interface IRewardService {

    /**
     * 处理奖励发放：匹配规则 → 幂等检查 → 库存校验 → 创建订单 → 发放记录
     *
     * @param userId     用户 ID
     * @param rewardType 奖励类型（sign / like）
     * @param targetId   目标标识（sign 为 yyyyMM，like 为 blog_id）
     */
    void processReward(Long userId, String rewardType, String targetId);
}
