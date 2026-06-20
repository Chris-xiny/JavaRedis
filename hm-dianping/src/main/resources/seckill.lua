--参数
local voucherId=ARGV[1]
local userId=ARGV[2]

--key
local stockKey='seckill:stock:'..voucherId
local orderKey='seckill:order:'..voucherId

local stock = redis.call('get', stockKey)
if tonumber(stock) <= 0 then
    return 1   -- 库存不足
end

--判断用户是否重复下单
if(redis.call('sismember',orderKey,userId)==1) then
    return 2
end

--减库存
redis.call('incrby',stockKey,-1)

--下单(保存用户)
redis.call('sadd',orderKey,userId)
return 0