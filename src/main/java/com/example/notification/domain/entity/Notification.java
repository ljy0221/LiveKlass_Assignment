package com.example.notification.domain.entity;

import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.domain.enums.NotificationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 핵심 엔티티
 *
 * 설계 결정:
 * 1. idempotencyKey = channel + eventId + recipientId 조합으로 중복 발송 방지
 *    DB UNIQUE 제약으로 동시 요청도 정확히 한 건만 저장됨
 * 2. retryCount, lastFailureReason을 엔티티에 보관해 운영 가시성 확보
 * 3. scheduledAt이 null이면 즉시 발송(PENDING), non-null이면 예약 발송(SCHEDULED)
 */
@Entity
@Table(
    name = "notifications",
    indexes = {
        @Index(name = "idx_recipient_status", columnList = "recipientId, status"),
        @Index(name = "idx_status_created",   columnList = "status, createdAt"),
        @Index(name = "idx_scheduled_at",     columnList = "scheduledAt, status"),
        @Index(name = "idx_failed_retry",     columnList = "status, nextRetryAt")  // findRetryableNotifications 쿼리 최적화
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_idempotency_key", columnNames = "idempotencyKey")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    /** 중복 방지 키: {channel}:{type}:{eventId}:{recipientId} */
    @Column(nullable = false, length = 256)  // UNIQUE 제약은 @Table uniqueConstraints 로 단일 관리
    private String idempotencyKey;

    /** 이벤트 ID (수강 ID, 결제 ID 등 참조 데이터) */
    private String eventId;

    /** 강의 ID 등 추가 참조 데이터 */
    private String referenceId;

    /** 예약 발송 시각 (null = 즉시) */
    private LocalDateTime scheduledAt;

    /** 발송 성공 시각 */
    private LocalDateTime sentAt;

    /** 다음 재시도 허용 시각 (지수 백오프, FAILED 상태에서만 유효) */
    private LocalDateTime nextRetryAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 마지막으로 상태가 바뀐 시각 (stuck 감지에 활용) */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private int retryCount;

    @Column(length = 1000)
    private String lastFailureReason;

    /** 읽음 여부 (IN_APP 채널 전용) */
    private boolean read;

    /** 읽음 처리 시각 */
    private LocalDateTime readAt;

    @Builder
    private Notification(Long recipientId, NotificationType type, NotificationChannel channel,
                         String idempotencyKey, String eventId, String referenceId,
                         LocalDateTime scheduledAt) {
        this.recipientId    = recipientId;
        this.type           = type;
        this.channel        = channel;
        this.idempotencyKey = idempotencyKey;
        this.eventId        = eventId;
        this.referenceId    = referenceId;
        this.scheduledAt    = scheduledAt;
        this.status         = (scheduledAt != null)
                                ? NotificationStatus.SCHEDULED
                                : NotificationStatus.PENDING;
        this.createdAt      = LocalDateTime.now();
        this.updatedAt      = this.createdAt;
        this.retryCount     = 0;
        this.read           = false;
    }

    // ── 상태 전이 메서드 ────────────────────────────────────────────────────

    /**
     * PENDING / SCHEDULED → PROCESSING
     * worker가 선점할 때 호출. 선점 자체는 DB 낙관적 업데이트로 경쟁.
     */
    public void startProcessing() {
        assertTransitionAllowed(NotificationStatus.PROCESSING);
        this.status    = NotificationStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    /** PROCESSING → SENT */
    public void markSent() {
        assertTransitionAllowed(NotificationStatus.SENT);
        this.status    = NotificationStatus.SENT;
        this.sentAt    = LocalDateTime.now();
        this.updatedAt = this.sentAt;
    }

    /**
     * PROCESSING → FAILED (재시도 가능) 또는 DEAD_LETTER (한계 초과)
     *
     * @param reason              실패 사유 (로그용)
     * @param maxRetry            재시도 최대 횟수
     * @param initialDelaySeconds 1차 재시도 대기 시간(초)
     * @param multiplier          지수 백오프 배수 (예: 2.0 → 1분, 2분, 4분)
     */
    private static final int FAILURE_REASON_MAX_LENGTH = 1000;

    public void markFailed(String reason, int maxRetry,
                           long initialDelaySeconds, double multiplier) {
        this.retryCount++;
        // 1000자 초과 시 조용히 잘릴 수 있으므로 애플리케이션 레벨에서 안전하게 자른다
        this.lastFailureReason = (reason != null && reason.length() > FAILURE_REASON_MAX_LENGTH)
            ? reason.substring(0, FAILURE_REASON_MAX_LENGTH - 3) + "..."
            : reason;
        this.updatedAt = LocalDateTime.now();

        if (this.retryCount >= maxRetry) {
            this.status      = NotificationStatus.DEAD_LETTER;
            this.nextRetryAt = null;
        } else {
            this.status = NotificationStatus.FAILED;
            // 지수 백오프: initialDelay * multiplier^(retryCount-1)
            long delaySeconds = Math.round(initialDelaySeconds
                * Math.pow(multiplier, this.retryCount - 1));
            this.nextRetryAt = this.updatedAt.plusSeconds(delaySeconds);
        }
    }

    /**
     * SCHEDULED → PENDING (예약 시각 도달, 일반 발송 큐로 이동)
     * 실제 PROCESSING 선점은 이후 tryAcquire + dispatchById 가 처리.
     */
    public void activateScheduled() {
        requireStatus(NotificationStatus.SCHEDULED);
        this.status    = NotificationStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * FAILED → PENDING (재시도 큐 재진입)
     * 재시도 횟수는 유지하며 PENDING으로만 전환.
     */
    public void requeueForRetry() {
        assertTransitionAllowed(NotificationStatus.PENDING);
        this.status    = NotificationStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * PROCESSING stuck → PENDING (복구)
     * 서버 재시작/스레드 사망 후 일정 시간 이상 PROCESSING 상태인 경우 재대기열화.
     */
    public void recoverFromStuck() {
        requireStatus(NotificationStatus.PROCESSING);
        this.status    = NotificationStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * DEAD_LETTER → PENDING (수동 재시도, 재시도 횟수 초기화)
     *
     * 정책 결정:
     * 수동 재시도는 운영자가 원인을 확인하고 의도적으로 트리거하는 행위이므로
     * retryCount를 0으로 초기화하여 최대 재시도 기회를 다시 부여한다.
     * 자동 재시도(requeueForRetry)는 횟수를 유지해 무한 루프를 방지한다.
     */
    public void manualRetry() {
        requireStatus(NotificationStatus.DEAD_LETTER);
        this.status         = NotificationStatus.PENDING;
        this.retryCount     = 0;
        this.lastFailureReason = null;
        this.updatedAt      = LocalDateTime.now();
    }

    /** IN_APP 읽음 처리 */
    public void markRead() {
        if (this.read) return; // 이미 읽음 → no-op (멱등)
        this.read   = true;
        this.readAt = LocalDateTime.now();
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private void requireStatus(NotificationStatus required) {
        if (this.status != required) {
            throw new IllegalStateException(
                "상태 전이 불가: %s 상태에서만 가능합니다. 현재 상태: %s".formatted(required, this.status));
        }
    }

    private void assertTransitionAllowed(NotificationStatus target) {
        boolean allowed = switch (target) {
            // SCHEDULED → PROCESSING 직접 전이는 허용하지 않는다.
            // 정상 경로: SCHEDULED → activateScheduled() → PENDING → tryAcquire → PROCESSING
            case PROCESSING -> this.status == NotificationStatus.PENDING
                            || this.status == NotificationStatus.FAILED;
            case SENT       -> this.status == NotificationStatus.PROCESSING;
            case PENDING    -> this.status == NotificationStatus.FAILED
                            || this.status == NotificationStatus.PROCESSING;
            default         -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                "상태 전이 불가: %s → %s".formatted(this.status, target));
        }
    }
}
