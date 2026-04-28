package com.hmdp.ai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.io.Serial;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopAppointmentMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long appointmentId;
    private Long userId;
    private Long shopId;
    private String shopName;
    private String visitTime;
    private String contactPhone;
    private String remark;
}

