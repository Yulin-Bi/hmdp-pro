package com.hmdp.mq;

import com.hmdp.ai.entity.ShopAppointment;
import com.hmdp.ai.entity.ShopAppointmentMessage;
import com.hmdp.ai.service.IShopAppointmentService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
@RocketMQMessageListener(
        topic = "shop-appointment-topic",
        consumerGroup = "shop-appointment-consumer-group"
)
public class ShopAppointmentConsumer implements RocketMQListener<ShopAppointmentMessage> {

    @Resource
    private IShopAppointmentService shopAppointmentService;

    @Override
    public void onMessage(ShopAppointmentMessage message) {
        log.info("收到预约消息：{}", message);
        if (message == null || message.getAppointmentId() == null) {
            log.warn("预约消息为空，忽略处理");
            return;
        }
        if (shopAppointmentService.getById(message.getAppointmentId()) != null) {
            log.info("预约已存在，跳过重复消息，appointmentId: {}", message.getAppointmentId());
            return;
        }

        LocalDateTime visitTime = parseVisitTime(message.getVisitTime());
        if (visitTime == null) {
            throw new IllegalArgumentException("预约时间格式不正确: " + message.getVisitTime());
        }

        ShopAppointment appointment = new ShopAppointment()
                .setUserId(message.getUserId())
                .setShopId(message.getShopId())
                .setShopName(message.getShopName())
                .setVisitTime(visitTime)
                .setContactPhone(message.getContactPhone())
                .setRemark(message.getRemark())
                .setStatus(0)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        appointment.setId(message.getAppointmentId());

        boolean saved = shopAppointmentService.save(appointment);
        if (!saved) {
            throw new RuntimeException("预约保存失败");
        }
    }

    private LocalDateTime parseVisitTime(String visitTimeText) {
        if (visitTimeText == null || visitTimeText.trim().isEmpty()) {
            return null;
        }
        List<DateTimeFormatter> formatters = Arrays.asList(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(visitTimeText, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}

