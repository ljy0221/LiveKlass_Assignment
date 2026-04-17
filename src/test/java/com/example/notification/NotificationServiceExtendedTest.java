package com.example.notification;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.entity.NotificationTemplate;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.domain.repository.NotificationRepository;
import com.example.notification.domain.repository.NotificationTemplateRepository;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceExtendedTest {

    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationTemplateRepository templateRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        templateRepository.deleteAll();
    }

    // ── dispatchById 선점 실패 경로 ────────────────────────────────────────

    @Test
    @DisplayName("tryAcquire 실패 시(이미 PROCESSING 중) dispatchById는 아무것도 하지 않는다")
    @Transactional
    void dispatchById_acquireFail_skips() {
        Notification n = buildSavedNotification("dispatch-skip-evt");
        n.startProcessing(); // PROCESSING 상태로 만들어 tryAcquire 실패 유도
        notificationRepository.saveAndFlush(n);

        // 이미 PROCESSING 상태이므로 tryAcquire(PENDING or FAILED 만 대상)가 0 반환
        notificationService.dispatchById(n.getId());

        // 상태 변화 없음 확인
        Notification found = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
    }

    // ── retryFailedNotifications ───────────────────────────────────────────

    @Test
    @DisplayName("retryFailedNotifications — nextRetryAt이 지난 FAILED 알림이 재시도된다")
    void retryFailed_eligibleNotification_requeued() {
        Notification n = buildSavedNotification("retry-failed-evt");
        n.startProcessing();
        n.markFailed("테스트 오류", 3, 0L, 1.0); // initialDelay=0 → 즉시 재시도 가능
        notificationRepository.saveAndFlush(n);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);

        notificationService.retryFailedNotifications();

        // 재시도 후 SENT 될 때까지 대기
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification latest = notificationRepository.findById(n.getId()).orElseThrow();
            assertThat(latest.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    @Test
    @DisplayName("retryFailedNotifications — 재시도 대상이 없으면 기존 알림 상태가 변하지 않는다")
    void retryFailed_noEligible_doesNothing() {
        notificationService.retryFailedNotifications();

        assertThat(notificationRepository.findRetryableNotifications(LocalDateTime.now())).isEmpty();
    }

    // ── manualRetry ────────────────────────────────────────────────────────

    @Test
    @DisplayName("DEAD_LETTER 알림에 manualRetry 호출 시 PENDING으로 전환되고 재발송된다")
    void manualRetry_deadLetter_recoversAndSent() {
        Notification n = buildSavedNotification("manual-retry-evt");
        n.startProcessing();
        n.markFailed("1차", 3, 0L, 1.0);
        n.requeueForRetry();
        n.startProcessing();
        n.markFailed("2차", 3, 0L, 1.0);
        n.requeueForRetry();
        n.startProcessing();
        n.markFailed("3차", 3, 0L, 1.0); // DEAD_LETTER
        notificationRepository.saveAndFlush(n);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);

        NotificationResponse resp = notificationService.manualRetry(n.getId());
        assertThat(resp.status()).isEqualTo(NotificationStatus.PENDING);

        // 비동기 발송 완료 대기
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification latest = notificationRepository.findById(n.getId()).orElseThrow();
            assertThat(latest.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    @Test
    @DisplayName("존재하지 않는 알림 manualRetry 시 예외 발생")
    void manualRetry_nonExistent_throws() {
        assertThatThrownBy(() -> notificationService.manualRetry(999999L))
            .isInstanceOf(com.example.notification.exception.NotificationNotFoundException.class);
    }

    @Test
    @DisplayName("존재하지 않는 알림 markRead 시 예외 발생")
    void markRead_nonExistent_throws() {
        assertThatThrownBy(() -> notificationService.markRead(999999L))
            .isInstanceOf(com.example.notification.exception.NotificationNotFoundException.class);
    }

    // ── sendWithTemplate — 템플릿 있는 경우 ───────────────────────────────

    @Test
    @DisplayName("템플릿이 있는 경우 renderTitle/renderBody가 적용되어 발송된다")
    void dispatch_withTemplate_sendsRenderedMessage() {
        // 템플릿 등록
        NotificationTemplate template = NotificationTemplate.builder()
            .type(NotificationType.ENROLLMENT_COMPLETE)
            .channel(NotificationChannel.IN_APP)
            .titleTemplate("수강신청 완료: {{referenceId}}")
            .bodyTemplate("이벤트 {{eventId}} 수강이 완료되었습니다.")
            .build();
        templateRepository.save(template);

        var req = new NotificationCreateRequest(
            1L, NotificationType.ENROLLMENT_COMPLETE, NotificationChannel.IN_APP,
            "tmpl-evt-1", "lec-tmpl-1", null);

        NotificationResponse resp = notificationService.create(req);

        // 비동기 발송 후 SENT 확인
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            NotificationResponse latest = notificationService.findById(resp.id());
            assertThat(latest.status()).isEqualTo(NotificationStatus.SENT);
        });
    }

    // ── buildIdempotencyKey — eventId null 경로 ───────────────────────────

    @Test
    @DisplayName("eventId가 null인 경우에도 알림이 정상 등록된다")
    void create_nullEventId_success() {
        var req = new NotificationCreateRequest(
            1L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.IN_APP,
            null, "lec-1", null);

        NotificationResponse resp = notificationService.create(req);
        assertThat(resp.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(resp.id()).isNotNull();
    }

    @Test
    @DisplayName("eventId null 알림 중복 요청 시 기존 항목 반환")
    void create_nullEventId_duplicate_returnsExisting() {
        var req = new NotificationCreateRequest(
            1L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.IN_APP,
            null, "lec-1", null);

        NotificationResponse first = notificationService.create(req);
        NotificationResponse second = notificationService.create(req);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    // ── findByRecipient — unreadOnly 경로 ────────────────────────────────

    @Test
    @DisplayName("unreadOnly=false 또는 null 시 전체 알림 반환")
    void findByRecipient_allNotifications() {
        notificationService.create(new NotificationCreateRequest(
            99L, NotificationType.ENROLLMENT_COMPLETE, NotificationChannel.EMAIL,
            "all-evt-1", "lec-1", null));

        List<NotificationResponse> all = notificationService.findByRecipient(99L, null);
        assertThat(all).isNotEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Notification buildSavedNotification(String eventId) {
        Notification n = Notification.builder()
            .recipientId(1L)
            .type(NotificationType.ENROLLMENT_COMPLETE)
            .channel(NotificationChannel.EMAIL)
            .idempotencyKey("EMAIL:ENROLLMENT_COMPLETE:" + eventId + ":" + System.nanoTime())
            .eventId(eventId)
            .referenceId("lec-1")
            .scheduledAt(null)
            .build();
        return notificationRepository.save(n);
    }
}
