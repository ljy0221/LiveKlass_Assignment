package com.example.notification;

import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.dto.request.NotificationCreateRequest;
import com.example.notification.domain.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("존재하지 않는 알림 markRead 시 404 반환")
    void markRead_nonExistent_returns404() throws Exception {
        mockMvc.perform(patch("/notifications/{id}/read", 999999L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("존재하지 않는 알림 manualRetry 시 404 반환")
    void manualRetry_nonExistent_returns404() throws Exception {
        mockMvc.perform(post("/notifications/{id}/retry", 999999L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("DEAD_LETTER 아닌 상태의 알림 manualRetry 시 400 반환")
    void manualRetry_nonDeadLetter_returns400() throws Exception {
        // PENDING 상태 알림 생성
        var req = new NotificationCreateRequest(
            1L, NotificationType.ENROLLMENT_COMPLETE, NotificationChannel.EMAIL,
            "evt-retry-400", "lec-1", null);
        MvcResult result = mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andReturn();

        Long id = objectMapper.readTree(result.getResponse().getContentAsString())
            .at("/data/id").longValue();

        // PENDING 상태에서 manualRetry → IllegalStateException → 400
        mockMvc.perform(post("/notifications/{id}/retry", id))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("필수 필드 누락 요청 시 400 반환 — validation 오류 메시지 포함")
    void post_multipleValidationErrors_returns400() throws Exception {
        String invalidBody = """
            {
              "type": "ENROLLMENT_COMPLETE",
              "channel": "EMAIL",
              "eventId": "evt-1"
            }
            """;

        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").exists());
    }
}
