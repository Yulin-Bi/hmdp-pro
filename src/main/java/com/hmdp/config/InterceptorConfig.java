package com.hmdp.config;

import com.hmdp.inter.LoginInterceptor;
import com.hmdp.inter.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

// 注册拦截器
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    // 注入redis
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 登录校验
    // 刷新token校验
    // 并且要指定顺序
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加需要拦截的
        // 过滤无需拦截的
        // 现在主要是要校验登录 所以除了登录以外的都不拦截
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/uoload/**",
                        "/user/code",
                        "/user/login"
                ).order(1);

        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns(
                        "/**"  //拦截所有
                ).order(0);
    }



}
