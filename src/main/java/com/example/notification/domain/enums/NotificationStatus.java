package com.example.notification.domain.enums;

/**
 * 알림 상태 전이
 *
 * PENDING ──(worker 선점)──▶ PROCESSING ──(성공)──▶ SENT
 *                               │
 *                            (실패)
 *                               │
 *                           FAILED ──(재시도)──▶ PROCESSING
 *                               │
 *                        (3회 초과)
 *                               │
 *                          DEAD_LETTER
 *
 * SCHEDULED: 발송 예약 시 PENDING 대신 사용
 */
public enum NotificationStatus {
    PENDING,
    SCHEDULED,
    PROCESSING,
    SENT,
    FAILED,
    DEAD_LETTER
}
