package com.example.notification.config;

import com.example.notification.domain.entity.NotificationTemplate;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.domain.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 타입×채널별 기본 템플릿 초기화 및 ShedLock 테이블 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final NotificationTemplateRepository templateRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // ShedLock 테이블 생성 (JPA DDL 자동 생성 범위 밖이므로 직접 생성)
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS shedlock (
                name        VARCHAR(64)  NOT NULL,
                lock_until  TIMESTAMP    NOT NULL,
                locked_at   TIMESTAMP    NOT NULL,
                locked_by   VARCHAR(255) NOT NULL,
                PRIMARY KEY (name)
            )
        """);
        log.info("ShedLock 테이블 준비 완료");

        if (templateRepository.count() > 0) return;

        List<NotificationTemplate> templates = List.of(
            // EMAIL 템플릿
            NotificationTemplate.builder()
                .type(NotificationType.ENROLLMENT_COMPLETE)
                .channel(NotificationChannel.EMAIL)
                .titleTemplate("[수강 신청 완료] 강의 등록이 확인되었습니다.")
                .bodyTemplate("안녕하세요! 강의(ID: {{referenceId}}) 수강 신청이 완료되었습니다. 수강 신청 번호: {{eventId}}")
                .build(),
            NotificationTemplate.builder()
                .type(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.EMAIL)
                .titleTemplate("[결제 완료] 결제가 확정되었습니다.")
                .bodyTemplate("결제(ID: {{eventId}}) 가 정상적으로 완료되었습니다. 강의 ID: {{referenceId}}")
                .build(),
            NotificationTemplate.builder()
                .type(NotificationType.LECTURE_START_REMINDER)
                .channel(NotificationChannel.EMAIL)
                .titleTemplate("[강의 시작 D-1] 내일 강의가 시작됩니다.")
                .bodyTemplate("강의(ID: {{referenceId}}) 가 내일 시작됩니다. 준비물을 확인해 주세요.")
                .build(),
            NotificationTemplate.builder()
                .type(NotificationType.ENROLLMENT_CANCELLED)
                .channel(NotificationChannel.EMAIL)
                .titleTemplate("[수강 취소 완료] 수강 신청이 취소되었습니다.")
                .bodyTemplate("수강 신청(ID: {{eventId}}) 이 취소되었습니다. 환불은 영업일 기준 3~5일 내 처리됩니다.")
                .build(),

            // IN_APP 템플릿
            NotificationTemplate.builder()
                .type(NotificationType.ENROLLMENT_COMPLETE)
                .channel(NotificationChannel.IN_APP)
                .titleTemplate("수강 신청 완료")
                .bodyTemplate("강의({{referenceId}}) 수강 신청이 완료되었습니다.")
                .build(),
            NotificationTemplate.builder()
                .type(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.IN_APP)
                .titleTemplate("결제 확정")
                .bodyTemplate("결제({{eventId}}) 가 확정되었습니다.")
                .build(),
            NotificationTemplate.builder()
                .type(NotificationType.LECTURE_START_REMINDER)
                .channel(NotificationChannel.IN_APP)
                .titleTemplate("강의 시작 D-1")
                .bodyTemplate("내일 강의({{referenceId}}) 가 시작됩니다!")
                .build(),
            NotificationTemplate.builder()
                .type(NotificationType.ENROLLMENT_CANCELLED)
                .channel(NotificationChannel.IN_APP)
                .titleTemplate("수강 취소")
                .bodyTemplate("수강 신청({{eventId}}) 이 취소 처리되었습니다.")
                .build()
        );

        templateRepository.saveAll(templates);
        log.info("알림 템플릿 {}건 초기화 완료", templates.size());
    }
}
