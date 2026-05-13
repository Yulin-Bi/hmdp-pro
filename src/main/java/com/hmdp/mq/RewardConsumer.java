package com.hmdp.mq;

import com.hmdp.dto.RewardMessage;
import com.hmdp.service.IRewardService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
@RocketMQMessageListener(
        topic = "reward-topic",
        consumerGroup = "reward-consumer-group"
)
public class RewardConsumer implements RocketMQListener<RewardMessage> {

    @Resource
    private IRewardService rewardService;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void onMessage(RewardMessage message) {
        log.info("收到奖励消息：{}", message);

        Long userId = message.getUserId();
        RLock lock = redissonClient.getLock("lock:reward:" + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            log.warn("奖励处理中，userId:{}", userId);
            return;
        }

        try {
            rewardService.processReward(
                    message.getUserId(),
                    message.getRewardType(),
                    message.getTargetId());
        } catch (Exception e) {
            log.error("处理奖励异常，message:{}", message, e);
        } finally {
            lock.unlock();
        }
    }
}
