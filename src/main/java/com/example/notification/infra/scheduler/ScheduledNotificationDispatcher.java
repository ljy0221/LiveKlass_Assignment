package com.example.notification.infra.scheduler;

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

/**
 * 예약 발송 스케줄러 (선택 구현)
 *
 * 1분 간격으로 scheduledAt <= now 인 SCHEDULED 상태 알림을 스캔해
 * PENDING으로 전환 후 발송 이벤트를 발행한다.
 *
 * 정밀도: 최대 1분 오차. 더 정밀한 예약이 필요하면 DelayQueue 또는 Kafka delay topic 활용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledNotificationDispatcher {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedRateString = "${notification.scheduler.fix-rate-ms:60000}")
    @SchedulerLock(name = "dispatchScheduledNotifications", lockAtMostFor = "PT90S", lockAtLeastFor = "PT10S")
    public void dispatchScheduled() {
        doDispatch(LocalDateTime.now());
    }

    @Transactional
    public void doDispatch(LocalDateTime now) {
        List<Notification> due = notificationRepository.findScheduledNotificationsDue(now);

        if (due.isEmpty()) return;

        log.info("예약 발송 대상 {}건 처리 시작", due.size());

        for (Notification n : due) {
            log.info("예약 발송 활성화: id={} scheduledAt={}", n.getId(), n.getScheduledAt());
            // activateScheduled()만 호출한다 — startProcessing()을 여기서 호출하면
            // tryAcquire가 PROCESSING 상태를 걸러내 발송이 스킵되는 버그가 생긴다.
            n.activateScheduled();
            eventPublisher.publishEvent(new NotificationDispatchEvent(n.getId()));
        }
    }
}
