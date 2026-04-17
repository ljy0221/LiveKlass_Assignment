package com.example.notification.domain.service;

import com.example.notification.config.NotificationProperties;
import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.entity.NotificationTemplate;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.infra.event.NotificationDispatchEvent;
import com.example.notification.domain.repository.NotificationRepository;
import com.example.notification.domain.repository.NotificationTemplateRepository;
import com.example.notification.dto.request.NotificationCreateRequest;
import com.example.notification.dto.response.NotificationResponse;
import com.example.notification.exception.NotificationNotFoundException;
import com.example.notification.infra.channel.NotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository templateRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationProperties props;
    private final List<NotificationSender> senders;
    private final NotificationPersistenceHelper persistenceHelper;

    // 채널 → 전략 라우팅 맵 (생성 시점에 한 번 초기화)
    private Map<NotificationChannel, NotificationSender> senderMap;

    @jakarta.annotation.PostConstruct
    void initSenderMap() {
        senderMap = Collections.unmodifiableMap(
            senders.stream()
                .collect(Collectors.toMap(NotificationSender::channel, Function.identity()))
        );
    }

    // ── 알림 등록 ──────────────────────────────────────────────────────────

    /**
     * 알림 발송 요청 접수
     *
     * 트랜잭션 격리 설계:
     * - create() 자체는 @Transactional 없음. 각 DB 작업을 REQUIRES_NEW 헬퍼가 담당한다.
     * - 저장 시도(saveAndPublish) 실패 시 Hibernate 세션 오염이 외부로 전파되지 않는다.
     * - 커밋 후 AFTER_COMMIT 이벤트가 발행되어 비동기 발송 스레드가 기동된다.
     *
     * 중복 방지 (이중 방어):
     * 1차 — 애플리케이션: findByKey로 기존 레코드 확인 후 반환 (순차 중복)
     * 2차 — DB UNIQUE 제약: 동시 요청에서 한 건만 INSERT 성공 (레이스 컨디션)
     *        DataIntegrityViolationException / UnexpectedRollbackException 발생 시
     *        fetchByIdempotencyKey(REQUIRES_NEW)로 기존 항목 반환
     */
    public NotificationResponse create(NotificationCreateRequest req) {
        String key = buildIdempotencyKey(req);

        // 1차: 이미 존재하면 기존 항목 반환
        Optional<NotificationResponse> existing = persistenceHelper.findByKey(key);
        if (existing.isPresent()) {
            log.debug("중복 요청 — 기존 알림 반환 key={}", key);
            return existing.get();
        }

        // 2차: 신규 저장 시도 (REQUIRES_NEW 격리 트랜잭션)
        Notification notification = Notification.builder()
            .recipientId(req.recipientId())
            .type(req.type())
            .channel(req.channel())
            .idempotencyKey(key)
            .eventId(req.eventId())
            .referenceId(req.referenceId())
            .scheduledAt(req.scheduledAt())
            .build();

        try {
            return persistenceHelper.saveAndPublish(notification);
        } catch (DataIntegrityViolationException | org.springframework.transaction.UnexpectedRollbackException e) {
            // 동시 요청이 먼저 커밋 → UNIQUE 위반. 새 트랜잭션에서 기존 항목 반환.
            log.debug("UNIQUE 위반(동시 요청) — 기존 알림 반환 key={}", key);
            return persistenceHelper.fetchByIdempotencyKey(key);
        }
    }

    // ── 비동기 발송 처리 ───────────────────────────────────────────────────

    /**
     * 트랜잭션 커밋 완료 후 비동기로 실행
     *
     * 설계:
     * 1. tryAcquire UPDATE로 낙관적 선점 → 다중 인스턴스 환경에서도 단일 처리 보장
     * 2. 발송 실패 시 markFailed → 재시도 스케줄러가 FAILED 상태를 감지해 재진입
     * 3. 예외가 트랜잭션 외부에서 발생하므로 알림 상태만 FAILED로 기록하고 전파하지 않음
     *
     * 주의: @Async 는 별도 스레드에서 실행되므로 this.dispatchById() 직접 호출 시
     *        Spring AOP 프록시를 우회해 @Transactional 이 무시된다.
     *        따라서 handleDispatchEvent 자체에 @Transactional 을 선언해
     *        비동기 스레드 안에서 새 트랜잭션을 직접 시작한다.
     */
    @Async("notificationExecutor")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDispatchEvent(NotificationDispatchEvent event) {
        dispatchById(event.notificationId());
    }

    public void dispatchById(Long notificationId) {
        int acquired = notificationRepository.tryAcquire(notificationId, LocalDateTime.now());
        if (acquired == 0) {
            log.debug("알림 선점 실패 (다른 인스턴스가 처리 중) id={}", notificationId);
            return;
        }

        Notification notification = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        try {
            sendWithTemplate(notification);
            notification.markSent();
            log.info("알림 발송 성공 id={}", notificationId);
        } catch (Exception e) {
            var retry = props.getRetry();
            notification.markFailed(e.getMessage(), retry.getMaxAttempts(),
                retry.getInitialDelaySeconds(), retry.getMultiplier());
            log.warn("알림 발송 실패 id={} retryCount={} status={} nextRetryAt={} reason={}",
                notificationId, notification.getRetryCount(), notification.getStatus(),
                notification.getNextRetryAt(), e.getMessage());
        }
    }

    // ── 재시도 ─────────────────────────────────────────────────────────────

    /**
     * FAILED 상태 알림 재시도
     * nextRetryAt <= now 인 알림만 대상으로 삼아 정확한 지수 백오프를 적용.
     */
    @Transactional
    public void retryFailedNotifications() {
        List<Notification> retryables =
            notificationRepository.findRetryableNotifications(LocalDateTime.now());

        for (Notification n : retryables) {
            log.info("재시도 큐 진입 id={} retryCount={}", n.getId(), n.getRetryCount());
            n.requeueForRetry();
            eventPublisher.publishEvent(new NotificationDispatchEvent(n.getId()));
        }
    }

    // ── 조회 ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public NotificationResponse findById(Long id) {
        return notificationRepository.findById(id)
            .map(NotificationResponse::from)
            .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> findByRecipient(Long recipientId, Boolean unreadOnly) {
        if (unreadOnly != null && unreadOnly) {
            // unreadOnly=true: DB 레벨에서 status=SENT AND read=false 조건을 함께 처리
            return notificationRepository.findUnreadSentByRecipientId(recipientId)
                .stream()
                .map(NotificationResponse::from)
                .toList();
        }
        // 전체 조회: 모든 상태 포함 — status 조건 없는 전용 메서드로 쿼리 단순화
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId)
            .stream()
            .map(NotificationResponse::from)
            .toList();
    }

    // ── 읽음 처리 ──────────────────────────────────────────────────────────

    /**
     * 읽음 처리 (멱등)
     *
     * 여러 기기에서 동시 읽음 요청 시 처리 정책:
     * markRead()는 이미 read=true이면 no-op(멱등)이므로 중복 호출 안전.
     * 더 정교한 처리가 필요하다면 UPDATE ... WHERE read=false 단일 쿼리로 교체 가능.
     */
    @Transactional
    public void markRead(Long notificationId) {
        Notification n = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        n.markRead();
    }

    // ── 수동 재시도 ────────────────────────────────────────────────────────

    @Transactional
    public NotificationResponse manualRetry(Long notificationId) {
        Notification n = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        n.manualRetry();
        eventPublisher.publishEvent(new NotificationDispatchEvent(n.getId()));
        log.info("수동 재시도 등록 id={}", notificationId);
        return NotificationResponse.from(n);
    }

    // ── private helpers ────────────────────────────────────────────────────

    private void sendWithTemplate(Notification notification) {
        Optional<NotificationTemplate> templateOpt = templateRepository
            .findByTypeAndChannel(notification.getType(), notification.getChannel());

        String title;
        String body;
        if (templateOpt.isPresent()) {
            var t = templateOpt.get();
            title = t.renderTitle(notification.getEventId(), notification.getReferenceId());
            body  = t.renderBody(notification.getEventId(), notification.getReferenceId());
        } else {
            title = notification.getType().name();
            body  = "알림이 발송되었습니다.";
        }

        NotificationSender sender = senderMap.get(notification.getChannel());
        if (sender == null) {
            throw new IllegalStateException("지원하지 않는 채널: " + notification.getChannel());
        }
        sender.send(notification, title, body);
    }

    private String buildIdempotencyKey(NotificationCreateRequest req) {
        return "%s:%s:%s:%d".formatted(
            req.channel(), req.type(),
            req.eventId() != null ? req.eventId() : "no-event",
            req.recipientId()
        );
    }
}
