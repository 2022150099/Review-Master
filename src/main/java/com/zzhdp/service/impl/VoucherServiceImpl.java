package com.zzhdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhdp.dto.Result;
import com.zzhdp.entity.Voucher;
import com.zzhdp.mapper.VoucherMapper;
import com.zzhdp.entity.SeckillVoucher;
import com.zzhdp.service.ISeckillVoucherService;
import com.zzhdp.service.IVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.zzhdp.utils.RedisConstants.SECKILL_STOCK_KEY;

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
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {


    private final ISeckillVoucherService seckillVoucherService;

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        //保存秒杀库存到Redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY+voucher.getId(),voucher.getStock().toString());
    }
}
