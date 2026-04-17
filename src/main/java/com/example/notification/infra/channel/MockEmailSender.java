package com.example.notification.infra.channel;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 이메일 발송 Mock 구현체
 * 운영 전환 시 JavaMailSender / AWS SES 구현체로 교체.
 */
@Slf4j
@Component
public class MockEmailSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification notification, String title, String body) {
        log.info("[EMAIL 발송] to=recipient:{} | subject={} | body={}",
            notification.getRecipientId(), title, body);
        // 실제 구현: mailSender.send(MimeMessage) 등
    }
}
