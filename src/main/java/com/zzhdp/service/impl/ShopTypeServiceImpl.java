package com.zzhdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zzhdp.dto.Result;
import com.zzhdp.entity.ShopType;
import com.zzhdp.mapper.ShopTypeMapper;
import com.zzhdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.zzhdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {

        //查询redis
        String type = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        //判断是否存在
        //存在
        if (StrUtil.isNotBlank(type)) {
            //转成List对象直接返回
            List<ShopType> typeList = JSONUtil.toList(type, ShopType.class);
            return Result.ok(typeList);
        }

        //不存在
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //将List存到redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);

    }
}
