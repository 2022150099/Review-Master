package com.zzhdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.zzhdp.dto.Result;
import com.zzhdp.entity.VoucherOrder;
import com.zzhdp.mapper.VoucherOrderMapper;
import com.zzhdp.service.ISeckillVoucherService;
import com.zzhdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhdp.utils.RedisIdWorker;
import com.zzhdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;

    private final RedisIdWorker redisIdWorker;

//    private final VoucherOrderServiceImpl voucherOrderService;

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderTask());
    }
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    private class VoucherOrderTask implements Runnable{
        String queueName="stream.orders";

        @Override
        public void run() {
            while (true){
                try {
                    //获取消息队列中的订单信息 xreadgroup group g1 c1
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //如果没有获取成功，循环
                        continue;
                    }
                    //如果获取成功，可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }

            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //获取pending-list中的订单信息 xreadgroup group g1 c1
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));


                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //如果没有获取成功，循环
                        break;
                    }
                    //如果获取成功，可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理pending-list异常",e);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }

                }

            }
        }

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    private class VoucherOrderTask implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                    //创建订单
                } catch (InterruptedException e) {
                   log.error("处理订单异常",e);
                }

            }
        }*/

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //获取用户
            Long userId = voucherOrder.getUserId();
            //创建锁对象
            RLock lock = redissonClient.getLock("lock:order:" + userId);

            //获取锁对象
            if (!lock.tryLock()){
                //获取锁失败，返回错误或重试
                log.error("不允许重复下单！");
                return ;
            }
            try {
                proxy.createVoucherOrder(voucherOrder);

            }finally {
                //释放锁
                lock.unlock();
            }

        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),
                String.valueOf(orderId)
        );

        //判断结果是否为0
        int r = result.intValue();
        if (r!=0){
            //不为0,代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);

    }



    /*@Override
    public Result seckillVoucher(Long voucherId) {

        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        //判断结果是否为0
        int r = result.intValue();
        if (r!=0){
            //不为0,代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //为0,有购买资格，把下单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        //返回订单id
        return Result.ok(orderId);

    }
*/


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //已经结束
//            return Result.fail("秒杀已经结束！");
//        }
//
//        //判断库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足！");
//        }
//        //先获取锁提交事务再释放锁
//        //锁，toString()方法底层new String(),所以不管userId相同都会创建锁，所以intern是让字符串去常量池寻找。
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        if (!lock.tryLock()) {
//            //获取锁失败，返回错误或重试
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//
//            //使用该方法的话在没有加上事务的情况下，引用加上事务的方法，出现this.调用，该方法没有事务。
////            return createVoucherOrder(voucherId);
////            return voucherOrderService.createVoucherOrder(voucherId);
//
//            //获取Aop上下文中的代理对象,因为其代理对象由spring创建和管理
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//
//        }finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        //一人一单
        Long userId = voucherOrder.getUserId();
        //查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断是否存在
        if (count>0){

            log.error("用户已经购买过一次");
            return;
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)//相当于乐观锁
                .update();
        if (!success){
            log.error("库存不足");
        }
        save(voucherOrder);
    }
}
