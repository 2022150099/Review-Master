package com.zzhdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zzhdp.dto.Result;
import com.zzhdp.entity.Shop;
import com.zzhdp.mapper.ShopMapper;
import com.zzhdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhdp.utils.CacheClient;
import com.zzhdp.utils.RedisData;
import com.zzhdp.utils.SystemConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.zzhdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    private final StringRedisTemplate stringRedisTemplate;

    private final CacheClient cacheClient;


    @Override
    public Result queryById(Long id) {

        //缓存穿透
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期来解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop==null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);

    }



    public Shop queryWithMutex(Long id)  {
        //1.从redis中查询商品缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回
            return  JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的是否是空值
        if (shopJson!=null){
            return null;

        }

        Shop shop=null;
        //实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock){
                //失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.成功,查询数据库
            shop = getById(id);

            //5.不存在,返回错误
            if (shop==null){
                //将空值写到redis中
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //6.存在,写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);


        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }


        //7.返回

        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);

    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);

        //封转逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {

        //利用主动更新更新商铺信息
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();

    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if (x==null||y==null){
            //不需要坐标查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        //计算分页参数
        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end= current*SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis，按照距离排序，分页，返回结果
        String key = SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );


        //解析id
        if (results==null){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids=new ArrayList<>(list().size());
        Map<String,Distance> distanceMap=new HashMap<>(list().size());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size()<=from){
            //没有下一页
            return Result.ok(Collections.emptyList());
        }
        //截取from-end
        list.stream().skip(from).forEach(result->{
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询出shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," +idStr+ ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
