package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    // 由于session可以通过session复制在集群服务器中复制，但是这样占用空间大，而且有延迟影响体验。
    // 所以选择将登录信息保存在redis中，性能更好。
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // MybatisPlus 可以实现单表增删改查 即ServiceImpl<UserMapper, User>
    // 验证码发送功能
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、校验手机号 利用工具类
        // 2、不符合返回错误
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 3、符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4、保存验证码到session
//        session.setAttribute("code", code);

        // 4、保存验证码到redis 并设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5、返回验证码到客户端
        log.debug("发送验证码成功，验证码：{}", code);
        // 6、返回结果
        return Result.ok();
    }

    // 登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1、再次校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        // 2、校验验证码
        String code = loginForm.getCode(); // 前端传来的验证码
        //Object cacheCode = session.getAttribute("code"); // session保存的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        // 3、不一致报错
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        // 4、一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 5、不存在，创建新用户并保存
        if (user == null) {
            user = creatUserWithPhone(phone);
        }
        // 6、保存用户信息到session并返回结果【注意不用存完整信息 第一是内存压力 第二是敏感信息】
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 6、保存用户信息到redis
        // 首先生成随机token作为令牌 然后将user对象转为hash存储
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        // 存储
        // 注意要给一个有效期，否则会一直保存在内存中。
        // 注意session是访问一次就会刷新有效期，而这里是不会刷新的
        // 所以要设置一个刷新，则在拦截器中设置token的刷新
        // 需要将 Long 类型的 id 转为 String
        // 因为在实体类中 id 属性设置为 Long 类型
        userMap.put("id", userDTO.getId().toString());
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 7、返回结果
        return Result.ok(token);
    }

    // 签到记录
    @Override
    public Result signCount() {
        // 1、获取用户
        Long userId = UserHolder.getUser().getId();
        // 2、获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3、拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4、判断当前日期是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5、获取本月截至今天的所有签到记录 返回十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        // 6、循环遍历
        if (result == null || result.size() == 0) {
            return Result.ok();
        }
        Long num = result.get(0);
        if (num == null || num == 0L){
            return Result.ok();
        }
        int count = 0;
        while (true){
            // 61、让数字与1 得到数字的最后一个bit
            // 62、判断这个bit是否为1
            // 63、数字右移
            if ((num & 1) == 0) {
                // 未签到
                break;
            }else{
                count++;
            }
            num = num >>> 1;
        }
        return Result.ok(count);
    }

    // 签到功能
    @Override
    public Result sign() {
        // 1、获取用户
        Long userId = UserHolder.getUser().getId();
        // 2、获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3、拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4、判断当前日期是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5、写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    private User creatUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户到数据库
        // 利用MybatisPlus的save方法
        save(user);
        return user;
    }
}
