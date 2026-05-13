package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reward_record")
public class RewardRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 领券用户 ID
     */
    private Long userId;

    /**
     * 奖励类型：sign-签到，like-点赞
     */
    private String rewardType;

    /**
     * 目标标识（sign 为 yyyyMM 月份，like 为 blog_id）
     */
    private String targetId;

    /**
     * 发放的优惠券 ID
     */
    private Long voucherId;

    /**
     * 生成的券订单 ID
     */
    private Long voucherOrderId;

    /**
     * 发放时间
     */
    private LocalDateTime createTime;
}
