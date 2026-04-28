package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.io.Serial;
import java.time.LocalDateTime;

@TableName("tb_shop_appointment")
public class ShopAppointment implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long shopId;

    private String shopName;

    private LocalDateTime visitTime;

    private String contactPhone;

    private String remark;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public ShopAppointment setUserId(Long userId) {
        this.userId = userId;
        return this;
    }

    public Long getShopId() {
        return shopId;
    }

    public ShopAppointment setShopId(Long shopId) {
        this.shopId = shopId;
        return this;
    }

    public String getShopName() {
        return shopName;
    }

    public ShopAppointment setShopName(String shopName) {
        this.shopName = shopName;
        return this;
    }

    public LocalDateTime getVisitTime() {
        return visitTime;
    }

    public ShopAppointment setVisitTime(LocalDateTime visitTime) {
        this.visitTime = visitTime;
        return this;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public ShopAppointment setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
        return this;
    }

    public String getRemark() {
        return remark;
    }

    public ShopAppointment setRemark(String remark) {
        this.remark = remark;
        return this;
    }

    public Integer getStatus() {
        return status;
    }

    public ShopAppointment setStatus(Integer status) {
        this.status = status;
        return this;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public ShopAppointment setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
        return this;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public ShopAppointment setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
        return this;
    }
}