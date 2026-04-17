package com.example.notification.infra.scheduler;

import com.example.notification.config.NotificationProperties;
import com.example.notification.domain.entity.Notification;
import com.example.notification.infra.event.NotificationDispatchEvent;
import com.example.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 비정상 상태 알림 복구 스케줄러
 *
 * ┌─ 복구 대상 1: PROCESSING stuck ──────────────────────────────────────┐
 * │ 문제: 서버 재시작, OOM Kill, 스레드 사망 등으로 PROCESSING 영구 유지  │
 * │ 해결: thresholdMinutes 이상 PROCESSING → PENDING으로 되돌림           │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ 복구 대상 2: Stale PENDING ─────────────────────────────────────────┐
 * │ 문제: CallerRunsPolicy가 작동하지 못한 극단적 상황(앱 재시작 중 발행  │
 * │       된 이벤트 유실 등)에서 PENDING 알림이 영구 대기할 수 있음        │
 * │ 해결: thresholdMinutes 이상 PENDING 상태인 알림에 재발송 이벤트 발행  │
 * │       → tryAcquire가 PENDING 상태를 확인하므로 중복 처리 안전         │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * 다중 인스턴스 안전:
 * - @SchedulerLock 으로 한 인스턴스만 실행.
 * - 복구 후 tryAcquire 경쟁으로 실제 처리는 단일 인스턴스만 수행.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckRecoveryScheduler {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationProperties props;

    @Scheduled(fixedRateString = "${notification.stuck-recovery.fix-rate-ms:600000}")
    @SchedulerLock(name = "recoverStuckNotifications", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void recoverStuckNotifications() {
        int thresholdMinutes = props.getStuckRecovery().getThresholdMinutes();
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(thresholdMinutes);
        doRecover(threshold);
    }

    @Transactional
    public void doRecover(LocalDateTime threshold) {
        recoverProcessingStuck(threshold);
        recoverStalePending(threshold);
    }

    // ── PROCESSING stuck 복구 ─────────────────────────────────────────────

    private void recoverProcessingStuck(LocalDateTime threshold) {
        recoverAndPublish(
            notificationRepository.findStuckNotifications(threshold),
            "PROCESSING stuck 복구",
            Notification::recoverFromStuck   // PROCESSING → PENDING (상태 검증 포함)
        );
    }

    // ── Stale PENDING 복구 ────────────────────────────────────────────────

    /**
     * CallerRunsPolicy의 보조 안전망.
     * 스레드 풀 포화 직전 reject 되거나 앱 재시작으로 누락된 PENDING 알림을 재발송.
     * tryAcquire 가 PENDING 상태를 확인하므로 이미 발송 중인 건은 자동으로 건너뜀.
     */
    private void recoverStalePending(LocalDateTime threshold) {
        recoverAndPublish(
            notificationRepository.findStalePendingNotifications(threshold),
            "Stale PENDING 재발행",
            n -> {}   // 상태 변경 없이 이벤트만 발행
        );
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────

    private void recoverAndPublish(List<Notification> list, String label, Consumer<Notification> preAction) {
        if (list.isEmpty()) return;
        log.warn("{} {}건 처리 시작 ({}분 이상 경과)",
            label, list.size(), props.getStuckRecovery().getThresholdMinutes());
        for (Notification n : list) {
            log.warn("{}: id={} updatedAt={}", label, n.getId(), n.getUpdatedAt());
            preAction.accept(n);
            eventPublisher.publishEvent(new NotificationDispatchEvent(n.getId()));
        }
    }
}
