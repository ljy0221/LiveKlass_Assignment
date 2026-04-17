package com.example.notification.infra.channel;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 인앱 알림 Mock 구현체
 * 운영 전환 시 SSE / WebSocket / Firebase FCM 구현체로 교체.
 */
@Slf4j
@Component
public class MockInAppSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public void send(Notification notification, String title, String body) {
        log.info("[IN_APP 발송] to=recipient:{} | title={} | body={}",
            notification.getRecipientId(), title, body);
        // 실제 구현: sseEmitter.send(SseEmitter.event().data(payload)) 등
    }
}
