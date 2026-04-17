package com.example.notification.domain.repository;

import com.example.notification.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /** 수신자 기준 전체 알림 목록 (상태 무관) */
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);

    /**
     * 읽지 않은 SENT 알림 조회 — unreadOnly=true 전용.
     * DB 레벨에서 status + read 조건을 함께 걸어 인메모리 필터를 제거한다.
     */
    @Query("""
        SELECT n FROM Notification n
        WHERE n.recipientId = :recipientId
          AND n.status = 'SENT'
          AND n.read = false
        ORDER BY n.createdAt DESC
    """)
    List<Notification> findUnreadSentByRecipientId(@Param("recipientId") Long recipientId);

    /** PROCESSING 상태로 일정 시간 이상 머문 stuck 알림 조회 */
    @Query("""
        SELECT n FROM Notification n
        WHERE n.status = 'PROCESSING'
          AND n.updatedAt < :threshold
        ORDER BY n.updatedAt ASC
        LIMIT 100
    """)
    List<Notification> findStuckNotifications(@Param("threshold") LocalDateTime threshold);

    /**
     * FAILED 중 nextRetryAt이 현재 시각 이전인 알림 조회.
     * nextRetryAt은 markFailed() 시점에 지수 백오프로 계산되어 저장된다.
     * idx_failed_retry(status, nextRetryAt) 인덱스로 스캔 최적화.
     */
    @Query("""
        SELECT n FROM Notification n
        WHERE n.status = 'FAILED'
          AND n.nextRetryAt <= :now
        ORDER BY n.nextRetryAt ASC
        LIMIT 100
    """)
    List<Notification> findRetryableNotifications(@Param("now") LocalDateTime now);

    /** 예약 발송 알림 조회 */
    @Query("""
        SELECT n FROM Notification n
        WHERE n.status = 'SCHEDULED'
          AND n.scheduledAt <= :now
        ORDER BY n.scheduledAt ASC
        LIMIT 100
    """)
    List<Notification> findScheduledNotificationsDue(@Param("now") LocalDateTime now);

    /**
     * 스레드 풀 포화나 앱 재시작 등으로 발송 이벤트가 누락된 PENDING 알림 조회.
     * updatedAt < threshold 조건으로 오래된 PENDING만 대상으로 삼는다.
     * CallerRunsPolicy의 보조 안전망 역할.
     */
    @Query("""
        SELECT n FROM Notification n
        WHERE n.status = 'PENDING'
          AND n.updatedAt < :threshold
        ORDER BY n.createdAt ASC
        LIMIT 100
    """)
    List<Notification> findStalePendingNotifications(@Param("threshold") LocalDateTime threshold);

    /**
     * 단일 UPDATE로 PENDING → PROCESSING 선점
     * 다중 인스턴스 환경에서 동일 알림을 한 인스턴스만 처리하도록 보장.
     * JPA 더티 체킹 대신 직접 UPDATE를 사용해 race condition 제거.
     */
    // clearAutomatically=true: UPDATE 후 1차 캐시를 비워 후속 findById가 stale 데이터를 반환하지 않도록 보장
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Notification n
        SET n.status = 'PROCESSING', n.updatedAt = :now
        WHERE n.id = :id
          AND n.status IN ('PENDING', 'FAILED')
    """)
    int tryAcquire(@Param("id") Long id, @Param("now") LocalDateTime now);

}
