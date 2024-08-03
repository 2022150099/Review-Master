package com.zzhdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhdp.dto.LoginFormDTO;
import com.zzhdp.dto.Result;
import com.zzhdp.dto.UserDTO;
import com.zzhdp.entity.User;
import com.zzhdp.mapper.UserMapper;
import com.zzhdp.service.IUserService;
import com.zzhdp.utils.RegexUtils;
import com.zzhdp.utils.SystemConstants;
import com.zzhdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.zzhdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合,返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3.符合,生成验证码
        String code = RandomUtil.randomNumbers(6);

//        //4.保存验证码到session
//        session.setAttribute("code",code);

        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码
        log.debug("发送短信验证码成功,验证码:{}",code);

        return Result.ok();

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            //2.如果不符合,返回错误信息
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码
        String cacheCode=  stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+loginForm.getPhone());
        String code=loginForm.getCode();
        if (cacheCode==null||!cacheCode.equals(code)){
            //不一致,报错
            return Result.fail("验证码错误");
        }
        //3.根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //4.判断用户是否存在
        if (user==null){
            //不存在,创建新用户,并将用户保存在数据库中
           user = createUserWithPhone(loginForm.getPhone());

        }
        //6.保存用户信息到redis中
        //6.使用UUID随机生成token
        String token  = UUID.randomUUID().toString(true);
        //将User对象变成UserDto
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将UserDto构造成hash
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        for (Map.Entry<String, Object> entry : userMap.entrySet()) {
            userMap.put(entry.getKey(), entry.getValue().toString());
        }

        String tokenKey=LOGIN_USER_KEY+token;
        //存储
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);

        //返回token

        return Result.ok(token);

    }

    @Override
    public Result logout() {

        //获取当前的用户信息
        Long userId = UserHolder.getUser().getId();
        if (userId==null){
            return Result.fail("用户未登录");
        }
        //将redis中的信息删除
        stringRedisTemplate.delete(LOGIN_USER_KEY+userId);
        UserHolder.removeUser();
        return Result.ok();

    }

    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();

        //获取日期
        LocalDateTime now = LocalDateTime.now();

        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);


        return Result.ok();



    }

    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();

        //获取日期
        LocalDateTime now = LocalDateTime.now();

        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止今天为止的所有的签到记录,返回的是一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result==null||result.isEmpty()){
            //没有签到记录
            return Result.ok(0);
        }

        Long num = result.get(0);
        int count=0;
        if (num==null||num==0){
            return Result.ok(0);
        }
        //循环遍历，然后跟1做与运算
        while (true){
            if ((num & 1)==0){
                break;
            }else {
                count++;
            }
            num>>>=1;
        }
        return Result.ok(count);



    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;
    }
}
