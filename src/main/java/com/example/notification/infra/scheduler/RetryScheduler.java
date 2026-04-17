package com.example.notification.infra.scheduler;

import com.example.notification.domain.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FAILED 알림 재시도 스케줄러
 *
 * 재시도 정책 (지수 백오프):
 *   nextRetryAt 컬럼에 정확한 재시도 허용 시각이 기록되어 있으므로
 *   nextRetryAt <= now 인 알림만 처리한다.
 *   - 1차 실패 → 60초 후 (retryCount=1)
 *   - 2차 실패 → 120초 후 (retryCount=2)
 *   - 3차 실패 → DEAD_LETTER
 *
 * 설계 결정:
 * - 재시도 비즈니스 로직은 NotificationService에 위임해 레이어 경계를 유지한다.
 * - 서버 재시작 후에도 DB를 스캔해 미처리 FAILED 알림을 자동으로 재처리 → 유실 없음 보장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final NotificationService notificationService;

    @Scheduled(fixedRateString = "${notification.scheduler.fix-rate-ms:60000}")
    @SchedulerLock(name = "retryFailedNotifications", lockAtMostFor = "PT90S", lockAtLeastFor = "PT10S")
    public void retryFailedNotifications() {
        notificationService.retryFailedNotifications();
    }
}
