package com.example.notification;

import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.dto.request.NotificationCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /notifications — 202 Accepted 반환 및 PENDING 상태 확인")
    void post_notifications_returns202() throws Exception {
        var req = new NotificationCreateRequest(
            100L, NotificationType.ENROLLMENT_COMPLETE, NotificationChannel.EMAIL,
            "evt-ctrl-1", "lec-1", null);

        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.id").isNumber())
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /notifications/{id} — 상태 조회")
    void get_notification_byId() throws Exception {
        // 등록
        var req = new NotificationCreateRequest(
            101L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.IN_APP,
            "evt-ctrl-2", "lec-2", null);

        MvcResult createResult = mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andReturn();

        Long id = objectMapper.readTree(createResult.getResponse().getContentAsString())
            .at("/data/id").longValue();

        // 조회
        mockMvc.perform(get("/notifications/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.recipientId").value(101));
    }

    @Test
    @DisplayName("GET /users/{recipientId}/notifications — 목록 조회")
    void get_userNotifications() throws Exception {
        // 2건 등록
        for (int i = 0; i < 2; i++) {
            var req = new NotificationCreateRequest(
                200L, NotificationType.LECTURE_START_REMINDER, NotificationChannel.EMAIL,
                "evt-list-" + i, "lec-" + i, null);
            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());
        }

        mockMvc.perform(get("/users/{recipientId}/notifications", 200L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @DisplayName("PATCH /notifications/{id}/read — 읽음 처리 후 204")
    void patch_markRead() throws Exception {
        var req = new NotificationCreateRequest(
            300L, NotificationType.ENROLLMENT_CANCELLED, NotificationChannel.IN_APP,
            "evt-read-1", "lec-1", null);

        MvcResult result = mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andReturn();

        Long id = objectMapper.readTree(result.getResponse().getContentAsString())
            .at("/data/id").longValue();

        // SENT 될 때까지 대기
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            mockMvc.perform(get("/notifications/{id}", id))
                .andExpect(jsonPath("$.data.status").value("SENT"))
        );

        // 읽음 처리
        mockMvc.perform(patch("/notifications/{id}/read", id))
            .andExpect(status().isNoContent());

        // 읽음 확인
        mockMvc.perform(get("/notifications/{id}", id))
            .andExpect(jsonPath("$.data.read").value(true))
            .andExpect(jsonPath("$.data.readAt").isNotEmpty());
    }

    @Test
    @DisplayName("동일 이벤트 중복 요청 — 두 번째도 200이며 동일 id 반환")
    void duplicate_request_sameId() throws Exception {
        var req = new NotificationCreateRequest(
            400L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
            "evt-dup-ctrl", "lec-dup", null);

        MvcResult first = mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andReturn();

        MvcResult second = mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andReturn();

        Long firstId  = objectMapper.readTree(first.getResponse().getContentAsString()).at("/data/id").longValue();
        Long secondId = objectMapper.readTree(second.getResponse().getContentAsString()).at("/data/id").longValue();

        org.assertj.core.api.Assertions.assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    @DisplayName("존재하지 않는 알림 조회 시 404 반환")
    void get_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/notifications/{id}", 999999L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("필수 필드 누락 요청 시 400 반환")
    void post_missingField_returns400() throws Exception {
        String invalidBody = """
            {
              "recipientId": null,
              "type": "ENROLLMENT_COMPLETE",
              "channel": "EMAIL"
            }
            """;

        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("예약 발송 등록 시 SCHEDULED 상태 반환")
    void post_scheduled_notification() throws Exception {
        var req = new NotificationCreateRequest(
            500L, NotificationType.LECTURE_START_REMINDER, NotificationChannel.EMAIL,
            "evt-sched-ctrl", "lec-sched", LocalDateTime.now().plusHours(1));

        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
            .andExpect(jsonPath("$.data.scheduledAt").isNotEmpty());
    }
}
