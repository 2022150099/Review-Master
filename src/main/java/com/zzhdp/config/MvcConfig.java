package com.zzhdp.config;


import com.zzhdp.utils.LoginInterceptor;
import com.zzhdp.utils.RefreshTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@RequiredArgsConstructor
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
      //登录拦截器
      registry.addInterceptor(new LoginInterceptor())
              .excludePathPatterns(
                      "/user/code",
                      "/shop/**",
                      "/voucher/**",
                      "/shop-type/**",
                      "/upload/**",
                      "/blog/hot",
                      "/user/login"
              ).order(1);
      //token刷新拦截器
      registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }

}
