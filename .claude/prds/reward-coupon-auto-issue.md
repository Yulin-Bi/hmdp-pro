## 问题陈述

平台需要提升用户活跃度和商户曝光转化，目前签到和笔记点赞功能已有数据积累，但缺乏**激励闭环**——用户完成签到或产出优质内容后没有实质性奖励，导致留存和内容质量难以持续提升。

## 解决方案

设计用户互动激励闭环：用户累计签到 7 天自动获得平台满减券，用户笔记点赞达 100 自动获得该店铺的营销券。系统通过异步 MQ 消费规则匹配 → 库存扣减 → 自动入账的方式完成发券，使用唯一索引保证奖励发放幂等。

## 用户故事

1. 作为平台用户，当我本月累计签到满 7 天时，自动收到一张平台优惠券，让我感受到被奖励从而保持活跃
2. 作为平台用户，当我发布的探店笔记点赞数达到 100 时，自动收到该店铺的优惠券，激励我继续产出优质内容
3. 作为平台运营，我可以在后台配置奖励规则（触发类型、阈值、发哪张券），灵活调整运营策略
4. 作为平台运营，我能查看奖励发放记录，追踪激励活动的效果
5. 作为系统，同一用户同月的签到奖励只发放一次，同一篇笔记的点赞奖励只发放一次，防止重复发放

## 实现决策

- **新建 `tb_reward_rule` 表**：配置奖励规则，字段含 reward_type（`sign`/`like`）、threshold（阈值天数/点赞数）、voucher_id（发放的券）、shop_id（店铺券关联店铺，平台券为 NULL）、status（启用状态）
- **新建 `tb_reward_record` 表**：记录每笔奖励发放，字段含 user_id、reward_type、target_id（sign 时为 yyyyMM 月份，like 时为 blog_id）、voucher_id、voucher_order_id、create_time。唯一索引 `UNIQUE(user_id, reward_type, target_id)` 保证幂等
- **RocketMQ 异步处理**：新建 `reward-topic`，签到/点赞达标后投递 `RewardMessage {userId, rewardType, targetId}`，由 `RewardConsumer` 消费处理
- **消费者发券流程**：匹配规则 → 唯一索引幂等检查 → 库存校验（Redisson 分布式锁防并发）→ 事务写入（INSERT tb_voucher_order + INSERT tb_reward_record + UPDATE tb_seckill_voucher.stock）
- **改造签到接口**：`UserServiceImpl.sign()` 签到完成后执行 BITCOUNT 统计当月累计天数，达标则发 MQ
- **改造点赞接口**：`BlogServiceImpl.likeBlog()` 点赞更新完成后执行 ZCARD 获取当前点赞数，达标则发 MQ（targetId 为笔记作者的 userId）
- **MQ 发送失败不影响主流程**：catch 异常并打日志，确保签到/点赞操作本身不受影响

## 测试决策

- 好测试标准：只测试外部行为（签到后是否产生了 MQ 消息、规则匹配后是否正确发券），不测试实现细节
- `RewardConsumer.processReward()` 是核心测试对象：需覆盖正常发券、重复消费幂等、库存不足、规则未启用等场景
- 参考项目中 `VoucherOrderConsumer` 的测试模式

## 非范围

- 不涉及前端奖励券展示页面的开发
- 不涉及后台规则管理接口（奖励规则通过 SQL 直接配置）
- 不涉及优惠券核销/退款流程
- 不涉及达人或笔记的达人认定逻辑（笔记作者即为达人）

## 补充说明

- 签到 BitMap 和笔记点赞 ZSet 的存储和统计已有实现，本次仅在现有流程上增加阈值检测和 MQ 投递
- 签到阈值 7 天、点赞阈值 100 赞为硬编码默认值，同时支持通过 `tb_reward_rule` 规则覆盖
- 消费者消费失败时返回 ACK（不在 MQ 层重试），避免重复消费导致超发；由运营人工补发
