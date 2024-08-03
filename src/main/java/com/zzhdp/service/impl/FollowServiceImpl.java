package com.zzhdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzhdp.dto.Result;
import com.zzhdp.dto.UserDTO;
import com.zzhdp.entity.Follow;
import com.zzhdp.mapper.FollowMapper;
import com.zzhdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhdp.service.IUserService;
import com.zzhdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    private final StringRedisTemplate stringRedisTemplate;

    private final IUserService userService;
    //关注和取关
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //判断到底是关注还是取关
        if (isFollow){
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                //把关注用户的id,放入redis的set集合
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
              }

        }else{
            //取关，删除
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));

            if (isSuccess){
                //把关注用户的id从redis的set集合移除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();

    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();

        //查询是否关注
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        //判断
        return Result.ok(count > 0);


    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //求交集
        String key2 = "follow:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf)
                .collect(Collectors.toList());

        //查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);


    }
}
