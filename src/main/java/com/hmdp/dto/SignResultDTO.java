package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class SignResultDTO {
    /**
     * 当月累计签到天数
     */
    private Integer totalCount;

    /**
     * 当月连续签到天数（从今天往回数）
     */
    private Integer continuousCount;

    /**
     * 当月每日签到状态，长度为当月天数，1=已签 0=未签
     */
    private List<Integer> dailyStatus;

    /**
     * 签到奖励进度（null 表示不适用）
     */
    private RewardProgress reward;

    @Data
    public static class RewardProgress {
        private String type;
        private Integer threshold;
        private Integer current;
        private String description;
    }
}
