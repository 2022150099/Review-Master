-- 参数列表
-- 1.1 优惠卷id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]
-- 1.3 订单id
local orderId = ARGV[3]

-- 数据key
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- 脚本业务
local stock = redis.call("get", stockKey)
if stock == nil then
    -- 库存不存在或为nil，返回1
    return 1
end

local stockNum = tonumber(stock)
if stockNum <= 0 then
    -- 库存不足，返回1
    return 1
end

local isMember = redis.call("sismember", orderKey, userId)
if isMember == 1 then
    -- 用户已经领取了
    return 2
end

-- 扣库存
redis.call("incrby", stockKey, -1)

-- 记录用户
redis.call("sadd", orderKey, userId)

-- 发送消息到队列中
redis.call("xadd", "stream.orders", "*", "userId", userId, "voucherId", voucherId, "id", orderId)

return 0
