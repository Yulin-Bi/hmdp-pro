package com.hmdp.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.ai.entity.ShopAppointment;
import com.hmdp.ai.mapper.ShopAppointmentMapper;
import com.hmdp.ai.service.IShopAppointmentService;
import org.springframework.stereotype.Service;

@Service
public class ShopAppointmentServiceImpl extends ServiceImpl<ShopAppointmentMapper, ShopAppointment> implements IShopAppointmentService {
}