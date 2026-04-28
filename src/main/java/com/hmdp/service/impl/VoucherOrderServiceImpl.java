package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderMessage;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    RedisIdWorker redisIdWorker = new RedisIdWorker();

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    // lua脚本定义引入
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final String TOPIC_ORDER = "voucher-order-topic";

    // 创建线程池 单线程
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

//    // 阻塞队列：有元素的时候才唤醒 取出元素执行
//    private BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
//    // 创建线程任务 runnable
//    @PostConstruct // 初始化方法 在类初始化完毕后执行
//    public void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderTask());
//    }
//    private class VoucherOrderTask implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 获取阻塞队列中的订单信息
//                    VoucherOrder voucherOrder = blockingQueue.take();
//                    // 创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    // 优化：在消息队列中处理
    // 创建线程任务 runnable
//    @PostConstruct // 初始化方法 在类初始化完毕后执行
//    public void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderTask());
//    }
//
//
//    // 添加关闭标志
//    private volatile boolean isShutdown = false;
//
//    // 添加销毁方法
//    @PreDestroy
//    public void destroy() {
//        isShutdown = true;
//        SECKILL_ORDER_EXECUTOR.shutdownNow();
//    }
//
//    private class VoucherOrderTask implements Runnable {
//        @Override
//        public void run() {
//            while (!isShutdown) {
//                try {
//                    // 获取消息队列中的订单信息
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
//                    );
//                    // 判断消息是否获取成功
//                    // 若失败则说明没有消息
//                    if (list == null || list.isEmpty()) {
//                        continue;
//                    }
//                    // 从list中获取
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    // 若存在消息 创建订单
//                    handleVoucherOrder(voucherOrder);
//                    // ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
//                } catch (Exception e) {
//                    if (isShutdown) {
//                        log.info("订单处理线程已关闭");
//                        break;
//                    }
//                    log.error("处理订单异常", e);
//                    handlePendingList();
//                }
//            }
//        }
//        private void handlePendingList() {
//            while (!isShutdown) {
//                try {
//                    // 获取消息队列中的订单信息
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1),
//                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
//                    );
//                    // 获取失败 说明pendinglist里没有数据
//                    if (list == null || list.isEmpty()) {
//                        break;
//                    }
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    // 创建订单
//                    handleVoucherOrder(voucherOrder);
//                    // ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
//                } catch (Exception e) {
//                    if (isShutdown) {
//                        log.info("订单处理线程已关闭");
//                        break;
//                    }
//                    log.error("处理pending-list异常", e);
//                    // 休眠
//                    try {
//                        Thread.sleep(200);
//                    } catch ( InterruptedException e1) {
//                        e1.printStackTrace();
//                    }
//                }
//            }
//        }
//    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取id
        Long userId = voucherOrder.getUserId();
        // 创建锁对象并获取锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);  // redisson的不可重入锁
        // 可以设置锁的过期时间
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 阻止一个人并发多个订单
            log.error("不允许重复下单");
            return;
        }
        // 需要拿到当前对象的代理对象
        // 代理对象掉函数会被spring管理
        // 需要依赖以及允许暴露
        try {
            //proxy.creatVoucherOrder(voucherOrder);
            creatVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    // 利用stream队列优化
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取参数
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1、执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                // 无key
                Collections.emptyList(),
                // 传递参数
                voucherId.toString(), userId.toString(),String.valueOf(orderId));

        // 2、判断脚本执行结果
        int r = result.intValue();
        if (r != 0) {
            switch (r) {
                case 1:
                    return Result.fail("库存不足");
                case 2:
                    return Result.fail("不能重复下单");
            }
        }

        VoucherOrderMessage message = new VoucherOrderMessage(orderId, userId, voucherId);
        Message<VoucherOrderMessage> mqMessage = MessageBuilder.withPayload(message).build();
        rocketMQTemplate.syncSend(TOPIC_ORDER, mqMessage);

        log.info("发送订单消息到RocketMQ成功，orderId: {}, userId: {}, voucherId: {}", orderId, userId, voucherId);

        // 4、返回订单id
        return Result.ok(orderId);
    }

//    // 异步实现秒杀卷抢购
//    private IVoucherOrderService proxy;
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、执行lua脚本
//        Long userId = UserHolder.getUser().getId();
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                // 无key
//                Collections.emptyList(),
//                // 传递参数
//                voucherId.toString(), userId.toString());
//        // 2、判断脚本执行结果
//        int r = result.intValue();
//        if (r != 0) {
//            switch (r) {
//                case 1:
//                    return Result.fail("库存不足");
//                case 2:
//                    return Result.fail("不能重复下单");
//            }
//        }
//        // 3、将订单信息保存到阻塞队列
//        long orderId = redisIdWorker.nextId("order");
//        // 异步执行：订单号 用户id 优惠券id
//        VoucherOrder order = new VoucherOrder();
//        order.setId(orderId);
//        order.setVoucherId(voucherId);
//        order.setUserId(userId);
//        // 创建阻塞队列并放入
//        blockingQueue.add(order);
//        // 获取代理对象【因为子线程没法获取到】
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        // 4、返回订单id
//        return Result.ok(orderId);
//    }

    @Transactional
    public void creatVoucherOrder(VoucherOrder order) {
        // 【一人一单】需要查询表中是否有订单
        // 注意并发的时候有可能并发一起抢购 可能同时没查询到


        // 注意锁用intern 保证用户id一样锁一样
        // 事务在锁释放后才提交 仍可能出现线程不安全
        // 所以锁需要锁整个方法
        Long userId = order.getUserId();
        boolean result = query().eq("user_id", userId)
                .eq("voucher_id", order.getVoucherId())
                .count() > 0;
        if (result) {
            log.error("用户已经购买过");
            return;
        }

        // 4、扣减库存
        // 【乐观锁】判断库存值是否与之前查到的一致
        // 但是太小心了
        // 所以让库存大于0
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 保存订单
        save(order);
        log.info("订单创建成功，orderId: {}", order.getId());
    }
}

//    // 秒杀卷抢购
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、查询优惠券信息 注意要去秒杀卷表中查而非优惠券表中
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2、判断秒杀是否开始结束
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//        // 3、判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        // 要先提交事务了再释放锁
//        Long userId = UserHolder.getUser().getId();
//
//        // 创建锁对象并获取锁
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);  // redisson的不可重入锁
//        // 可以设置锁的过期时间
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            // 阻止一个人并发多个订单
//            return Result.fail("获取锁失败");
//        }
//
//
//        // 需要拿到当前对象的代理对象
//        // 代理对象掉函数会被spring管理
//        // 需要依赖以及允许暴露
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    // 不同用户不同锁
    // 一下为单线程情况 多线程需要传整个订单信息
//    @Transactional
//    public Result creatVoucherOrder(Long voucherId) {
//        // 【一人一单】需要查询表中是否有订单
//        // 注意并发的时候有可能并发一起抢购 可能同时没查询到
//
//
//        // 注意锁用intern 保证用户id一样锁一样
//        // 事务在锁释放后才提交 仍可能出现线程不安全
//        // 所以锁需要锁整个方法
//        Long userId = UserHolder.getUser().getId();
//        boolean result = query().eq("user_id", userId)
//                .eq("voucher_id", voucherId)
//                .count() > 0;
//        if (result) {
//            return Result.fail("用户已抢购");
//        }
//
//        // 4、扣减库存
//        // 【乐观锁】判断库存值是否与之前查到的一致
//        // 但是太小心了
//        // 所以让库存大于0
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)
//                .update();
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//
//        // 5、创建订单
//        VoucherOrder order = new VoucherOrder();
//
//        // 添加订单id
//        long orderId = redisIdWorker.nextId("order");
//        order.setId(orderId);
//        // 用户id
//        order.setUserId(userId);
//        // 优惠券id
//        order.setVoucherId(voucherId);
//        // 保存订单
//        save(order);
//
//
//        // 6、返回订单结果
//        return Result.ok(orderId);
//    }
//}
