package com.example.notification.dto.response;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.domain.enums.NotificationType;

import java.time.LocalDateTime;

public record NotificationResponse(
    Long id,
    Long recipientId,
    NotificationType type,
    NotificationChannel channel,
    NotificationStatus status,
    String eventId,
    String referenceId,
    LocalDateTime scheduledAt,
    LocalDateTime sentAt,
    LocalDateTime createdAt,
    int retryCount,
    String lastFailureReason,
    boolean read,
    LocalDateTime readAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
            n.getId(),
            n.getRecipientId(),
            n.getType(),
            n.getChannel(),
            n.getStatus(),
            n.getEventId(),
            n.getReferenceId(),
            n.getScheduledAt(),
            n.getSentAt(),
            n.getCreatedAt(),
            n.getRetryCount(),
            n.getLastFailureReason(),
            n.isRead(),
            n.getReadAt()
        );
    }
}
