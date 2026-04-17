package com.example.notification.dto.request;

import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 알림 발송 요청 DTO
 *
 * scheduledAt이 null이면 즉시 발송(PENDING),
 * non-null이면 해당 시각에 발송 예약(SCHEDULED).
 */
public record NotificationCreateRequest(

    @NotNull(message = "수신자 ID는 필수입니다.")
    Long recipientId,

    @NotNull(message = "알림 타입은 필수입니다.")
    NotificationType type,

    @NotNull(message = "발송 채널은 필수입니다.")
    NotificationChannel channel,

    /** 이벤트 ID (수강 ID, 결제 ID 등). 중복 방지 키 구성에 사용. */
    @Size(max = 255, message = "eventId는 255자 이하여야 합니다.")
    String eventId,

    /** 강의 ID 등 부가 참조 데이터 */
    @Size(max = 255, message = "referenceId는 255자 이하여야 합니다.")
    String referenceId,

    /** 예약 발송 시각. null이면 즉시 발송. */
    @Future(message = "scheduledAt은 현재 시각 이후여야 합니다.")
    LocalDateTime scheduledAt
) {}
