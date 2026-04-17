package com.example.notification.infra.event;

/**
 * 알림 처리 요청 이벤트
 *
 * 현재: Spring ApplicationEventPublisher (In-Process)
 * 전환 경로: 이 클래스를 직렬화 가능하게 만들고 KafkaEventPublisher 구현체로 교체.
 * NotificationService는 EventPublisher 인터페이스에만 의존하므로 코드 변경 없음.
 */
public record NotificationDispatchEvent(Long notificationId) {}
