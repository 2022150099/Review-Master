package com.hmdp;

import com.zzhdp.entity.Shop;
import com.zzhdp.service.impl.ShopServiceImpl;
import com.zzhdp.utils.RedisIdWorker;


import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class ZzhDianPingApplicationTests {


    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es= Executors.newFixedThreadPool(500);
    @Test
    void test(){
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void testIdWorker() throws InterruptedException {

        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task=()->{
            for (int i = 0; i <100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id= "+id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time= "+(end-begin));

    }
    @Test
    public void loadShopData(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //把店铺分组，按照typeId分组
        Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for (Map.Entry<Long, List<Shop>> longListEntry : map.entrySet()) {
            Long typeId = longListEntry.getKey();
            List<Shop> shops = longListEntry.getValue();
            //写入redis geoadd key  longitude latitude member
            String key="shop:geo:"+typeId;
//            for(Shop shop:shops){
//                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString())
//            }
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>();
            for (Shop shop : shops){
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);


        }


        //分批完成写入redis

    }

    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j=0;
        for (int i = 0; i < 1000000; i++) {
            j=i%1000;
            values[j] = "user_"+i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("hll1",values);

            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hll1");
        System.out.println("count= "+count);
    }



}
