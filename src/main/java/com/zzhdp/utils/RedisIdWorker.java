package com.zzhdp.utils;


import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component

@RequiredArgsConstructor
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final long COUNT_BITS=1640995200L;
    private final StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {

        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSecond-BEGIN_TIMESTAMP;

        //生成序列号
        //获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix+":"+ date);

        return timestamp << COUNT_BITS|count;

    }
}
