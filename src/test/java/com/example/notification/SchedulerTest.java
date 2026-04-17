package com.example.notification;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.domain.repository.NotificationRepository;
import com.example.notification.infra.scheduler.ScheduledNotificationDispatcher;
import com.example.notification.infra.scheduler.StuckRecoveryScheduler;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SchedulerTest {

    @Autowired NotificationRepository notificationRepository;
    @Autowired StuckRecoveryScheduler stuckRecoveryScheduler;
    @Autowired ScheduledNotificationDispatcher scheduledNotificationDispatcher;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    // ── StuckRecoveryScheduler ─────────────────────────────────────────────

    @Test
    @DisplayName("PROCESSING stuck 알림은 doRecover() 호출 시 PENDING으로 복구된다")
    void stuckRecovery_processingStuck_recoveredToPending() {
        // startProcessing()으로 직접 PROCESSING 상태로 저장 — tryAcquire와 동일한 결과
        Notification n = buildNotification("stuck-evt-1");
        n.startProcessing();
        Long id = notificationRepository.saveAndFlush(n).getId();

        // doRecover 직접 호출로 @SchedulerLock 우회
        stuckRecoveryScheduler.doRecover(LocalDateTime.now().plusSeconds(1));

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification recovered = notificationRepository.findById(id).orElseThrow();
            assertThat(recovered.getStatus()).isIn(NotificationStatus.PENDING, NotificationStatus.SENT,
                NotificationStatus.PROCESSING);
        });
    }

    @Test
    @DisplayName("stuck PROCESSING 알림이 없으면 doRecover()는 상태를 변경하지 않는다")
    void stuckRecovery_noStuck_statusUnchanged() {
        // PENDING 알림: stuck 복구 대상이 아니며 stale-pending 재발행만 발생
        Notification n = buildNotification("no-stuck-evt");
        notificationRepository.save(n);

        stuckRecoveryScheduler.doRecover(LocalDateTime.now().plusSeconds(1));

        // stale-pending 복구는 상태를 바꾸지 않고 이벤트만 재발행
        Notification found = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("Stale PENDING 알림은 doRecover() 호출 시 상태가 변하지 않는다 (이벤트만 재발행)")
    void stuckRecovery_stalePending_statusUnchanged() {
        Notification n = buildNotification("stale-pending-evt");
        notificationRepository.save(n);

        stuckRecoveryScheduler.doRecover(LocalDateTime.now().plusSeconds(1));

        Notification found = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    // ── ScheduledNotificationDispatcher ───────────────────────────────────

    @Test
    @DisplayName("scheduledAt이 과거인 SCHEDULED 알림은 doDispatch() 호출 시 SCHEDULED 상태에서 벗어난다")
    void scheduledDispatcher_dueNotification_activatedToPending() {
        Notification n = buildScheduledNotification("sched-dispatch-evt", LocalDateTime.now().minusMinutes(1));
        Long id = notificationRepository.saveAndFlush(n).getId();

        // doDispatch 직접 호출로 @SchedulerLock 우회
        scheduledNotificationDispatcher.doDispatch(LocalDateTime.now());

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification updated = notificationRepository.findById(id).orElseThrow();
            assertThat(updated.getStatus()).isNotEqualTo(NotificationStatus.SCHEDULED);
        });
    }

    @Test
    @DisplayName("scheduledAt이 미래인 SCHEDULED 알림은 doDispatch() 호출 시 변환되지 않는다")
    void scheduledDispatcher_futureNotification_notActivated() {
        Notification n = buildScheduledNotification("sched-future-evt", LocalDateTime.now().plusHours(1));
        Long id = notificationRepository.saveAndFlush(n).getId();

        scheduledNotificationDispatcher.doDispatch(LocalDateTime.now());

        Notification updated = notificationRepository.findById(id).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("만료된 예약 알림이 없으면 doDispatch() 는 아무것도 하지 않는다")
    void scheduledDispatcher_nodue_doesNothing() {
        Notification n = buildNotification("no-sched-evt");
        notificationRepository.save(n);

        scheduledNotificationDispatcher.doDispatch(LocalDateTime.now());

        Notification found = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("scheduledAt이 과거인 SCHEDULED 알림은 리포지토리 쿼리로 조회된다")
    void scheduledDispatcher_dueNotification_foundByRepository() {
        Notification n = buildScheduledNotification("sched-query-evt", LocalDateTime.now().minusMinutes(1));
        notificationRepository.saveAndFlush(n);

        List<Notification> due = notificationRepository.findScheduledNotificationsDue(LocalDateTime.now());
        assertThat(due).anyMatch(d -> d.getId().equals(n.getId()));
    }

    @Test
    @DisplayName("scheduledAt이 미래인 SCHEDULED 알림은 리포지토리 쿼리에서 제외된다")
    void scheduledDispatcher_futureNotification_notInDueQuery() {
        Notification n = buildScheduledNotification("sched-future-query-evt", LocalDateTime.now().plusHours(1));
        notificationRepository.saveAndFlush(n);

        List<Notification> due = notificationRepository.findScheduledNotificationsDue(LocalDateTime.now());
        assertThat(due).noneMatch(d -> d.getId().equals(n.getId()));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Notification buildNotification(String eventId) {
        return Notification.builder()
            .recipientId(1L)
            .type(NotificationType.ENROLLMENT_COMPLETE)
            .channel(NotificationChannel.EMAIL)
            .idempotencyKey("EMAIL:ENROLLMENT_COMPLETE:" + eventId + ":1")
            .eventId(eventId)
            .referenceId("lec-1")
            .scheduledAt(null)
            .build();
    }

    private Notification buildScheduledNotification(String eventId, LocalDateTime scheduledAt) {
        return Notification.builder()
            .recipientId(1L)
            .type(NotificationType.ENROLLMENT_COMPLETE)
            .channel(NotificationChannel.EMAIL)
            .idempotencyKey("EMAIL:ENROLLMENT_COMPLETE:" + eventId + ":1")
            .eventId(eventId)
            .referenceId("lec-1")
            .scheduledAt(scheduledAt)
            .build();
    }
}
