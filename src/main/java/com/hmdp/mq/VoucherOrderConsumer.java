package com.hmdp.mq;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.entity.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
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
        topic = "voucher-order-topic",
        consumerGroup = "voucher-order-consumer-group"
)
public class VoucherOrderConsumer implements RocketMQListener<VoucherOrderMessage> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    // ... existing code ...
    @Override
    public void onMessage(VoucherOrderMessage message) {
        log.info("收到订单消息：{}", message);

        Long userId = message.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            log.error("不允许重复下单，userId: {}", userId);
            return;
        }

        try {
            VoucherOrder order = new VoucherOrder();
            // Deleted:BeanUtil.copyProperties(message, order);
            // 修复：显式赋值解决字段名不一致 (orderId -> id) 导致的复制失败问题
            order.setId(message.getOrderId());
            order.setUserId(message.getUserId());
            order.setVoucherId(message.getVoucherId());

            voucherOrderService.creatVoucherOrder(order);
        } catch (Exception e) {
            log.error("处理订单异常", e);
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
// ... existing code ...

}
