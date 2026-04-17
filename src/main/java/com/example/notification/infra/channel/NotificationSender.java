package com.example.notification.infra.channel;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.entity.NotificationTemplate;
import com.example.notification.domain.enums.NotificationChannel;

/**
 * 채널별 발송 전략 인터페이스
 *
 * 실제 운영 전환 시 구현체만 교체:
 * - EmailSender: Mock → JavaMailSender / AWS SES
 * - InAppSender: Mock → WebSocket / SSE / Firebase FCM
 */
public interface NotificationSender {

    NotificationChannel channel();

    /**
     * 실제 메시지 발송.
     * 네트워크 오류 등 일시적 장애는 RuntimeException으로 throw → 상위에서 재시도 처리.
     */
    void send(Notification notification, String title, String body);
}
