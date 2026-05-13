package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reward_rule")
public class RewardRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 触发类型：sign-签到，like-点赞
     */
    private String rewardType;

    /**
     * 触发阈值：签到累计天数 / 点赞数
     */
    private Integer threshold;

    /**
     * 发放的优惠券 ID
     */
    private Long voucherId;

    /**
     * 店铺 ID（like 类型关联店铺，sign 类型为 NULL）
     */
    private Long shopId;

    /**
     * 启用状态：0-禁用，1-启用
     */
    private Integer status;
}
