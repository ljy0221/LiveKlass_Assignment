package com.example.notification.domain.entity;

import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 알림 타입별 메시지 템플릿
 *
 * 운영 중 템플릿 변경은 DB 직접 업데이트 또는 관리 API를 통해 처리.
 * {{recipientId}}, {{eventId}}, {{referenceId}} 등 플레이스홀더 지원.
 */
@Entity
@Table(
    name = "notification_templates",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_type_channel",
        columnNames = {"type", "channel"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String titleTemplate;

    @Column(nullable = false, length = 2000)
    private String bodyTemplate;

    private static final String PLACEHOLDER_EVENT_ID     = "{{eventId}}";
    private static final String PLACEHOLDER_REFERENCE_ID = "{{referenceId}}";

    @Builder
    public NotificationTemplate(NotificationType type, NotificationChannel channel,
                                 String titleTemplate, String bodyTemplate) {
        this.type          = type;
        this.channel       = channel;
        this.titleTemplate = titleTemplate;
        this.bodyTemplate  = bodyTemplate;
    }

    public String renderTitle(String eventId, String referenceId) {
        return render(titleTemplate, eventId, referenceId);
    }

    public String renderBody(String eventId, String referenceId) {
        return render(bodyTemplate, eventId, referenceId);
    }

    private String render(String template, String eventId, String referenceId) {
        return template
            .replace(PLACEHOLDER_EVENT_ID,     Objects.toString(eventId, ""))
            .replace(PLACEHOLDER_REFERENCE_ID, Objects.toString(referenceId, ""));
    }
}
