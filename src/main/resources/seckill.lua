--- 秒杀参数列表
------ STREAM 秒杀更新

--- 1、优惠卷id
local voucherId = ARGV[1]
--- 2、用户id
local userId = ARGV[2]
--- 新增:订单id
local orderId = ARGV[3]

--- 3、数据key 订单
local orderKey = "seckill:order:" .. voucherId
--- 4、库存key
local stockKey = "seckill:stock:" .. voucherId
--- 5、判断库存
local stock = redis.call("get", stockKey)
if (stock == false or stock == nil) then
    return 1 -- 库存不存在或已售罄
end
if (tonumber(stock) <= 0) then
    return 1 -- 库存不足
end
--- 判断用户是否重复下单
if (redis.call("sismember", orderKey, userId) == 1) then
    --- 重复下单
    return 2
end
--- 扣减库存
redis.call("incrby", stockKey, -1)
--- 保存订单
redis.call("sadd", orderKey, userId)

--- 新增：发消息到队列中
--- redis.call("xadd", "stream.orders", "*", "userId", userId, "voucherId", voucherId, "id", orderId)
return 0


