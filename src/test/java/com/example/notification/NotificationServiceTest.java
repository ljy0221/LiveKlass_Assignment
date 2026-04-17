package com.example.notification;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.domain.repository.NotificationRepository;
import com.example.notification.domain.service.NotificationService;
import com.example.notification.dto.request.NotificationCreateRequest;
import com.example.notification.dto.response.NotificationResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceTest {

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationRepository notificationRepository;

    // markFailed 테스트용 공통 재시도 설정
    private static final int    MAX_RETRY      = 3;
    private static final long   INITIAL_DELAY  = 60L;
    private static final double MULTIPLIER     = 2.0;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    // ── 기본 발송 흐름 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("알림 등록 시 202 응답(PENDING 상태)이 즉시 반환된다")
    void create_returnsPending() {
        var req = req("evt-1", "lecture-1");
        NotificationResponse resp = notificationService.create(req);

        assertThat(resp.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(resp.id()).isNotNull();
    }

    @Test
    @DisplayName("비동기 처리 후 알림 상태가 SENT로 전환된다")
    void async_dispatch_marksSent() {
        var req = req("evt-2", "lecture-2");
        NotificationResponse resp = notificationService.create(req);
        Long id = resp.id();

        // 비동기 처리 완료 대기 (최대 5초)
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            NotificationResponse latest = notificationService.findById(id);
            assertThat(latest.status()).isEqualTo(NotificationStatus.SENT);
        });
    }

    // ── 중복 방지 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("동일 이벤트 순차 요청 시 두 번째는 기존 알림을 그대로 반환한다")
    void duplicate_sequential_returnsExisting() {
        var req = req("evt-dup", "lec-dup");

        NotificationResponse first  = notificationService.create(req);
        NotificationResponse second = notificationService.create(req);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 이벤트 동시 요청 시 정확히 1건만 저장된다")
    void duplicate_concurrent_exactlyOneStored() throws InterruptedException {
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        Set<Long> savedIds = ConcurrentHashMap.newKeySet();
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    NotificationResponse resp = notificationService.create(req("evt-concurrent", "lec-1"));
                    savedIds.add(resp.id());
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(savedIds).hasSize(1);
        assertThat(notificationRepository.count()).isEqualTo(1);
        assertThat(errors).isEmpty();
    }

    // ── 재시도 정책 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("FAILED 상태 알림은 retryCount가 maxAttempts 미만일 때 재시도 가능하다")
    @Transactional
    void retry_belowMaxAttempts_statusFailed() {
        Notification n = buildSavedNotification();

        n.startProcessing();
        n.markFailed("네트워크 오류", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getRetryCount()).isEqualTo(1);
        assertThat(n.getLastFailureReason()).isEqualTo("네트워크 오류");
    }

    @Test
    @DisplayName("maxAttempts 초과 실패 시 DEAD_LETTER로 전환된다")
    @Transactional
    void retry_exceedMaxAttempts_deadLetter() {
        Notification n = buildSavedNotification();

        n.startProcessing();
        n.markFailed("1차 실패", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);
        n.requeueForRetry();

        n.startProcessing();
        n.markFailed("2차 실패", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);
        n.requeueForRetry();

        n.startProcessing();
        n.markFailed("3차 실패", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);  // 3회 → DEAD_LETTER

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getRetryCount()).isEqualTo(3);
        assertThat(n.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("DEAD_LETTER 수동 재시도 시 retryCount가 0으로 초기화된다")
    @Transactional
    void manualRetry_resetsRetryCount() {
        Notification n = buildSavedNotification();

        n.startProcessing();
        n.markFailed("1차", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);
        n.requeueForRetry();
        n.startProcessing();
        n.markFailed("2차", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);
        n.requeueForRetry();
        n.startProcessing();
        n.markFailed("3차", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);

        n.manualRetry();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getRetryCount()).isEqualTo(0);
        assertThat(n.getLastFailureReason()).isNull();
    }

    @Test
    @DisplayName("1000자 초과 실패 사유는 999자 + '...'으로 잘린다")
    @Transactional
    void markFailed_longReason_truncated() {
        Notification n = buildSavedNotification();
        n.startProcessing();

        String longReason = "x".repeat(2000);
        n.markFailed(longReason, MAX_RETRY, INITIAL_DELAY, MULTIPLIER);

        assertThat(n.getLastFailureReason()).hasSizeLessThanOrEqualTo(1000);
        assertThat(n.getLastFailureReason()).endsWith("...");
    }

    // ── 지수 백오프 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("1차 실패 시 nextRetryAt = updatedAt + initialDelay(60s)")
    @Transactional
    void exponentialBackoff_firstFailure() {
        Notification n = buildSavedNotification();
        n.startProcessing();

        LocalDateTime before = LocalDateTime.now();
        n.markFailed("오류", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);
        LocalDateTime after = LocalDateTime.now();

        // nextRetryAt ≈ now + 60s
        assertThat(n.getNextRetryAt()).isNotNull();
        assertThat(n.getNextRetryAt()).isAfter(before.plusSeconds(59));
        assertThat(n.getNextRetryAt()).isBefore(after.plusSeconds(61));
    }

    @Test
    @DisplayName("2차 실패 시 nextRetryAt = updatedAt + 120s (multiplier=2 적용)")
    @Transactional
    void exponentialBackoff_secondFailure() {
        Notification n = buildSavedNotification();

        n.startProcessing();
        n.markFailed("1차 오류", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);
        n.requeueForRetry();

        n.startProcessing();
        LocalDateTime before = LocalDateTime.now();
        n.markFailed("2차 오류", MAX_RETRY, INITIAL_DELAY, MULTIPLIER);
        LocalDateTime after = LocalDateTime.now();

        // nextRetryAt ≈ now + 120s (60 * 2^1)
        assertThat(n.getNextRetryAt()).isAfter(before.plusSeconds(119));
        assertThat(n.getNextRetryAt()).isBefore(after.plusSeconds(121));
    }

    @Test
    @DisplayName("nextRetryAt 이전에는 findRetryableNotifications 에 포함되지 않는다")
    @Transactional
    void exponentialBackoff_notRetryableBeforeNextRetryAt() {
        Notification n = buildSavedNotification(); // PENDING

        // 엔티티 메서드로 직접 상태 전이 — @Transactional 테스트 내에서 안전
        n.startProcessing();
        n.markFailed("오류", MAX_RETRY, INITIAL_DELAY, MULTIPLIER); // nextRetryAt = now+60s
        notificationRepository.saveAndFlush(n); // DB에 반영 후 JPQL 쿼리 실행

        // now 기준으로는 아직 재시도 불가 (nextRetryAt이 미래)
        assertThat(notificationRepository.findRetryableNotifications(LocalDateTime.now()))
            .noneMatch(r -> r.getId().equals(n.getId()));

        // 충분히 지난 시각 기준으로는 재시도 가능
        assertThat(notificationRepository.findRetryableNotifications(LocalDateTime.now().plusSeconds(61)))
            .anyMatch(r -> r.getId().equals(n.getId()));
    }

    // ── Stuck 복구 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("stuck 복구 호출 시 PROCESSING 상태가 PENDING으로 전환된다")
    @Transactional
    void stuckRecovery_processingToPending() {
        Notification n = buildSavedNotification();
        n.startProcessing();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PROCESSING);

        n.recoverFromStuck();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("PROCESSING이 아닌 상태에서 recoverFromStuck() 호출 시 예외가 발생한다")
    @Transactional
    void stuckRecovery_throwsOnNonProcessing() {
        Notification n = buildSavedNotification(); // PENDING 상태

        assertThatThrownBy(n::recoverFromStuck)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PROCESSING");
    }

    // ── 예약 발송 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("scheduledAt이 있으면 SCHEDULED 상태로 등록된다")
    void scheduled_statusIsScheduled() {
        var req = new NotificationCreateRequest(
            1L,
            NotificationType.LECTURE_START_REMINDER,
            NotificationChannel.EMAIL,
            "evt-sched",
            "lec-sched",
            LocalDateTime.now().plusHours(24)
        );

        NotificationResponse resp = notificationService.create(req);
        assertThat(resp.status()).isEqualTo(NotificationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("activateScheduled() 호출 시 SCHEDULED → PENDING으로 전환된다")
    @Transactional
    void activateScheduled_transitionsToPending() {
        Notification n = Notification.builder()
            .recipientId(1L)
            .type(NotificationType.LECTURE_START_REMINDER)
            .channel(NotificationChannel.EMAIL)
            .idempotencyKey("EMAIL:LECTURE_START_REMINDER:sched-evt:" + System.nanoTime())
            .eventId("sched-evt")
            .referenceId("lec-sched")
            .scheduledAt(LocalDateTime.now().plusHours(1))
            .build();
        notificationRepository.save(n);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);

        n.activateScheduled();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("activateScheduled()는 SCHEDULED 이외 상태에서 예외를 던진다")
    @Transactional
    void activateScheduled_nonScheduledThrows() {
        Notification n = buildSavedNotification(); // PENDING 상태

        assertThatThrownBy(n::activateScheduled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SCHEDULED");
    }

    // ── 읽음 처리 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("읽음 처리는 멱등 — 여러 번 호출해도 readAt이 처음 값을 유지한다")
    @Transactional
    void markRead_idempotent() {
        Notification n = buildSavedNotification();

        n.markRead();
        LocalDateTime firstReadAt = n.getReadAt();

        n.markRead();

        assertThat(n.isRead()).isTrue();
        assertThat(n.getReadAt()).isEqualTo(firstReadAt);
    }

    // ── 잘못된 상태 전이 ───────────────────────────────────────────────────

    @Test
    @DisplayName("PENDING 상태에서 바로 markSent() 호출 시 예외가 발생한다")
    @Transactional
    void illegalTransition_throwsException() {
        Notification n = buildSavedNotification();

        assertThatThrownBy(n::markSent)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("상태 전이 불가");
    }

    // ── 조회 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("수신자 기준 알림 목록 조회 — unreadOnly=true 필터가 동작한다")
    void findByRecipient_unreadOnlyFilter() {
        notificationService.create(req("evt-a", "lec-a"));
        notificationService.create(new NotificationCreateRequest(
            1L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.IN_APP,
            "evt-b", "lec-b", null));

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationResponse> all = notificationService.findByRecipient(1L, null);
            long sentCount = all.stream()
                .filter(r -> r.status() == NotificationStatus.SENT)
                .count();
            assertThat(sentCount).isEqualTo(2);
        });

        List<NotificationResponse> all = notificationService.findByRecipient(1L, null);
        notificationService.markRead(all.get(0).id());

        List<NotificationResponse> unread = notificationService.findByRecipient(1L, true);
        assertThat(unread).hasSize(1);
        assertThat(unread.get(0).read()).isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private NotificationCreateRequest req(String eventId, String referenceId) {
        return new NotificationCreateRequest(
            1L,
            NotificationType.ENROLLMENT_COMPLETE,
            NotificationChannel.EMAIL,
            eventId,
            referenceId,
            null
        );
    }

    private Notification buildSavedNotification() {
        Notification n = Notification.builder()
            .recipientId(1L)
            .type(NotificationType.ENROLLMENT_COMPLETE)
            .channel(NotificationChannel.EMAIL)
            .idempotencyKey("EMAIL:ENROLLMENT_COMPLETE:test-evt:" + System.nanoTime())
            .eventId("test-evt")
            .referenceId("lec-1")
            .scheduledAt(null)
            .build();
        return notificationRepository.save(n);
    }
}
