package com.example.notification;

import com.example.notification.domain.entity.NotificationTemplate;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.domain.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotificationTemplateTest {

    @Autowired NotificationTemplateRepository templateRepository;

    @BeforeEach
    void setUp() {
        templateRepository.deleteAll();
    }

    @Test
    @DisplayName("템플릿 생성 및 저장 후 조회 가능하다")
    @Transactional
    void template_createAndFind() {
        NotificationTemplate template = NotificationTemplate.builder()
            .type(NotificationType.ENROLLMENT_COMPLETE)
            .channel(NotificationChannel.EMAIL)
            .titleTemplate("수강신청 완료: {{referenceId}}")
            .bodyTemplate("이벤트 {{eventId}} 수강 완료")
            .build();

        templateRepository.save(template);

        Optional<NotificationTemplate> found = templateRepository
            .findByTypeAndChannel(NotificationType.ENROLLMENT_COMPLETE, NotificationChannel.EMAIL);

        assertThat(found).isPresent();
        assertThat(found.get().getType()).isEqualTo(NotificationType.ENROLLMENT_COMPLETE);
        assertThat(found.get().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(found.get().getTitleTemplate()).contains("{{referenceId}}");
        assertThat(found.get().getBodyTemplate()).contains("{{eventId}}");
    }

    @Test
    @DisplayName("renderTitle은 플레이스홀더를 실제 값으로 대체한다")
    void template_renderTitle_replacesPlaceholders() {
        NotificationTemplate template = NotificationTemplate.builder()
            .type(NotificationType.LECTURE_START_REMINDER)
            .channel(NotificationChannel.IN_APP)
            .titleTemplate("강의 시작 알림 - {{referenceId}}")
            .bodyTemplate("강의 {{referenceId}}가 {{eventId}}에 시작합니다.")
            .build();

        String title = template.renderTitle("evt-123", "lec-456");

        assertThat(title).isEqualTo("강의 시작 알림 - lec-456");
        assertThat(title).doesNotContain("{{referenceId}}");
    }

    @Test
    @DisplayName("renderBody는 eventId와 referenceId 플레이스홀더를 모두 대체한다")
    void template_renderBody_replacesAllPlaceholders() {
        NotificationTemplate template = NotificationTemplate.builder()
            .type(NotificationType.PAYMENT_CONFIRMED)
            .channel(NotificationChannel.EMAIL)
            .titleTemplate("결제 완료")
            .bodyTemplate("결제 이벤트 {{eventId}}, 강의 {{referenceId}} 처리 완료")
            .build();

        String body = template.renderBody("pay-001", "course-999");

        assertThat(body).isEqualTo("결제 이벤트 pay-001, 강의 course-999 처리 완료");
    }

    @Test
    @DisplayName("eventId가 null인 경우 빈 문자열로 대체된다")
    void template_renderTitle_nullEventId_replacedWithEmpty() {
        NotificationTemplate template = NotificationTemplate.builder()
            .type(NotificationType.ENROLLMENT_CANCELLED)
            .channel(NotificationChannel.EMAIL)
            .titleTemplate("취소 알림 [{{eventId}}]")
            .bodyTemplate("취소 처리됨")
            .build();

        String title = template.renderTitle(null, "lec-1");

        assertThat(title).isEqualTo("취소 알림 []");
    }

    @Test
    @DisplayName("referenceId가 null인 경우 빈 문자열로 대체된다")
    void template_renderBody_nullReferenceId_replacedWithEmpty() {
        NotificationTemplate template = NotificationTemplate.builder()
            .type(NotificationType.ENROLLMENT_COMPLETE)
            .channel(NotificationChannel.IN_APP)
            .titleTemplate("알림")
            .bodyTemplate("강의 {{referenceId}} 수강 완료")
            .build();

        String body = template.renderBody("evt-1", null);

        assertThat(body).isEqualTo("강의  수강 완료");
    }

    @Test
    @DisplayName("플레이스홀더가 없는 템플릿은 그대로 반환된다")
    void template_render_noPlaceholder_returnsAsIs() {
        NotificationTemplate template = NotificationTemplate.builder()
            .type(NotificationType.PAYMENT_CONFIRMED)
            .channel(NotificationChannel.IN_APP)
            .titleTemplate("결제가 완료되었습니다.")
            .bodyTemplate("감사합니다.")
            .build();

        assertThat(template.renderTitle("evt", "ref")).isEqualTo("결제가 완료되었습니다.");
        assertThat(template.renderBody("evt", "ref")).isEqualTo("감사합니다.");
    }
}
