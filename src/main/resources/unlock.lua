if redis.call("get", KEYS[1]) == ARGV[1] then
    -- 释放锁
    return redis.call("del", KEYS[1])
else
    return 0
end