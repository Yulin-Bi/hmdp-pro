package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherOrderDTO {
    private Long id;
    private Long voucherId;
    private String title;
    private String subTitle;
    private Long payValue;
    private Long actualValue;
    private String rules;
    private Integer status;
    private String source;
    private String shopName;
    private Long shopId;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
}
