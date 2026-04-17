package com.example.notification;

import com.example.notification.dto.response.ApiResponse;
import com.example.notification.exception.DuplicateNotificationException;
import com.example.notification.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerUnitTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("DuplicateNotificationException → 409 Conflict")
    void handleDuplicate_returns409() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleDuplicate(new DuplicateNotificationException("test-key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).contains("test-key");
    }

    @Test
    @DisplayName("IllegalStateException → 400 Bad Request")
    void handleIllegalState_returns400() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleIllegalState(new IllegalStateException("잘못된 상태"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("일반 Exception → 500 Internal Server Error")
    void handleGeneral_returns500() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleGeneral(new RuntimeException("예상치 못한 오류"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }
}
