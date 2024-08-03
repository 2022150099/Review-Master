package com.zzhdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.zzhdp.utils.RedisConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;


    //写入redis,并设置TTL
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        //将对象序列化成string
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }


    //写入redis，并设置逻辑过期时间
    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit timeUnit){

        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }


    //利用缓存空值解决缓存穿透

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit timeUnit){
        String key=keyPrefix+id;
        //1.从redis中查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在,直接返回
             return JSONUtil.toBean(json, type);

        }

        //判断命中的是否是空值
        if (json!=null){
            return null;

        }

        //4.不存在,查询数据库
        R r=dbFallback.apply(id);

        //5.不存在,返回错误
        if (r==null){
            //将空值写到redis中
            this.set(key,"",time,timeUnit);

            return null;
        }

        //6.存在,写入redis
        this.set(key,r,time,timeUnit);

        //7.返回

        return r;
    }



    private static final ExecutorService CACHE_REBUILD_THREAD_POOL = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit timeUnit){
        String key=keyPrefix+id;
        //1.从redis中查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在,直接返回
            return null;

        }

        //命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return r;
        }

        //已过期，需要缓存重建
        //缓存重建
        //获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        if (isLock) {
            //成功，开启独立线程，创建线程，实现缓存重建
            CACHE_REBUILD_THREAD_POOL.submit(() -> {
                try {
                    //重建缓存
                    //查询数据库
                    R rl = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,rl,time,timeUnit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //返回过期的店铺信息
        return r;
    }

    //利用互斥锁解决缓存击穿
    public <R,ID> R queryWithMutex(String keyPrefix,ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit timeUnit)  {

        String key=keyPrefix+id;
        //1.从redis中查询商品缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回
            return  JSONUtil.toBean(shopJson, type);
        }

        //判断命中的是否是空值
        if (shopJson!=null){
            return null;

        }

        R r=null;
        //实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock){
                //失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,dbFallback,time,timeUnit);
            }

            //4.成功,查询数据库
            r=dbFallback.apply(id);

            //5.不存在,返回错误
            if (r==null){
                //将空值写到redis中
                this.set(key,"",time,timeUnit);
                return null;
            }

            //6.存在,写入redis
            this.set(key,r,time,timeUnit);


        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }


        //7.返回

        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);

    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
