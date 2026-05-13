package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.RewardMessage;
import com.hmdp.dto.SignResultDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.RewardRecord;
import com.hmdp.entity.RewardRule;
import com.hmdp.entity.User;
import com.hmdp.mapper.RewardRecordMapper;
import com.hmdp.mapper.RewardRuleMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private RewardRuleMapper rewardRuleMapper;

    @Resource
    private RewardRecordMapper rewardRecordMapper;

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
    public Result signCount(String month) {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();

        YearMonth yearMonth;
        int daysInMonth;
        if (month != null && !month.isEmpty()) {
            yearMonth = YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyyMM"));
            daysInMonth = yearMonth.lengthOfMonth();
        } else {
            yearMonth = YearMonth.from(now);
            daysInMonth = yearMonth.lengthOfMonth();
        }
        String keySuffix = yearMonth.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        int maxDay = yearMonth.equals(YearMonth.from(now)) ? now.getDayOfMonth() : daysInMonth;

        SignResultDTO dto = new SignResultDTO();

        // 获取 BitMap 并解析每日签到状态
        List<Long> bitField = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(maxDay)).valueAt(0));

        List<Integer> dailyStatus = new ArrayList<>(daysInMonth);
        Long num = (bitField != null && !bitField.isEmpty()) ? bitField.get(0) : 0L;
        if (num == null) num = 0L;

        int totalCount = 0;
        // BITFIELD GET uN 0 返回的值中，offset 0 是最高位
        // 所以 day i（对应 Redis offset i）在返回值中的 bit 位置是 (maxDay - 1 - i)
        for (int i = 0; i < maxDay; i++) {
            int signed = (int) ((num >>> (maxDay - 1 - i)) & 1);
            dailyStatus.add(signed);
            if (signed == 1) totalCount++;
        }
        // 剩余未到日期的补 0
        for (int i = maxDay; i < daysInMonth; i++) {
            dailyStatus.add(0);
        }

        // 连续签到天数（从今天往回数；历史月份从月末往回数）
        int startIdx = yearMonth.equals(YearMonth.from(now)) ? maxDay - 1 : daysInMonth - 1;
        int continuousCount = 0;
        for (int i = startIdx; i >= 0; i--) {
            if (dailyStatus.get(i) == 1) {
                continuousCount++;
            } else {
                break;
            }
        }

        dto.setTotalCount(totalCount);
        dto.setContinuousCount(continuousCount);
        dto.setDailyStatus(dailyStatus);

        // 签到奖励进度
        QueryWrapper<RewardRule> ruleWrapper = new QueryWrapper<>();
        ruleWrapper.eq("reward_type", "sign").eq("status", 1);
        RewardRule rule = rewardRuleMapper.selectOne(ruleWrapper);
        if (rule != null) {
            SignResultDTO.RewardProgress progress = new SignResultDTO.RewardProgress();
            progress.setType("sign");
            progress.setThreshold(rule.getThreshold());
            progress.setCurrent(totalCount);

            QueryWrapper<RewardRecord> recordWrapper = new QueryWrapper<>();
            recordWrapper.eq("user_id", userId)
                    .eq("reward_type", "sign")
                    .eq("target_id", yearMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
            Integer alreadyRewarded = rewardRecordMapper.selectCount(recordWrapper);
            if (alreadyRewarded != null && alreadyRewarded > 0) {
                progress.setDescription("本月已领取签到奖励");
            } else if (totalCount >= rule.getThreshold()) {
                progress.setDescription("已达成，优惠券已自动发放");
            } else {
                progress.setDescription("再签到" + (rule.getThreshold() - totalCount) + "天可得平台满减券");
            }
            dto.setReward(progress);
        }

        return Result.ok(dto);
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
        // 5、检查是否已签到
        Boolean alreadySigned = stringRedisTemplate.opsForValue().getBit(key, dayOfMonth - 1);
        if (Boolean.TRUE.equals(alreadySigned)) {
            return Result.fail("今日已签到");
        }
        // 6、写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        // 6、统计当月累计签到天数，达标则发送奖励消息
        try {
            Long signCount = stringRedisTemplate.execute(
                    (RedisCallback<Long>) connection ->
                            connection.bitCount(key.getBytes()));
            if (signCount != null && signCount >= 7) {
                String month = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
                RewardMessage message = new RewardMessage(userId, "sign", month);
                rocketMQTemplate.syncSend("reward-topic",
                        MessageBuilder.withPayload(message).build());
                log.debug("发送签到奖励消息成功，userId:{}, month:{}, signCount:{}", userId, month, signCount);
            }
        } catch (Exception e) {
            log.error("发送签到奖励消息异常，userId:{}", userId, e);
        }

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
