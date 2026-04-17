package com.example.notification.domain.service;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.infra.event.NotificationDispatchEvent;
import com.example.notification.domain.repository.NotificationRepository;
import com.example.notification.dto.response.NotificationResponse;
import com.example.notification.exception.DuplicateNotificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 알림 저장 트랜잭션 격리 헬퍼
 *
 * 문제 배경:
 * DataIntegrityViolationException 발생 시 Hibernate 세션은 rollback-only 상태가 된다.
 * 같은 세션에서 후속 쿼리를 실행하면 UnexpectedRollbackException 이 발생한다.
 *
 * 해결 전략:
 * - 저장 시도(saveAndPublish)와 충돌 해소(fetchByIdempotencyKey) 모두 REQUIRES_NEW 로 격리.
 * - 각 호출은 독립된 Hibernate 세션·DB 트랜잭션을 가지므로, 실패해도 외부 컨텍스트를 오염시키지 않는다.
 * - create()는 @Transactional 없이 두 REQUIRES_NEW 호출을 조율한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPersistenceHelper {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 새 알림 저장 + 이벤트 발행 (REQUIRES_NEW)
     * 커밋 후 AFTER_COMMIT 이벤트가 발행되어 비동기 발송 스레드가 기동된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationResponse saveAndPublish(Notification notification) {
        Notification saved = notificationRepository.save(notification);
        log.info("알림 접수 id={} key={} status={}", saved.getId(), saved.getIdempotencyKey(), saved.getStatus());

        if (saved.getStatus() == NotificationStatus.PENDING) {
            eventPublisher.publishEvent(new NotificationDispatchEvent(saved.getId()));
        }
        return NotificationResponse.from(saved);
    }

    /**
     * idempotencyKey로 기존 알림 조회 (REQUIRES_NEW)
     * DataIntegrityViolationException 으로 외부 세션이 파손된 이후에도 안전하게 실행된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<NotificationResponse> findByKey(String key) {
        return queryByKey(key);
    }

    /**
     * idempotencyKey로 기존 알림 조회 (REQUIRES_NEW), 없으면 예외
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public NotificationResponse fetchByIdempotencyKey(String key) {
        return queryByKey(key).orElseThrow(() -> new DuplicateNotificationException(key));
    }

    private Optional<NotificationResponse> queryByKey(String key) {
        return notificationRepository.findByIdempotencyKey(key).map(NotificationResponse::from);
    }
}
