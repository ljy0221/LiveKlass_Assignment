package com.example.notification.controller;

import com.example.notification.domain.service.NotificationService;
import com.example.notification.dto.request.NotificationCreateRequest;
import com.example.notification.dto.response.ApiResponse;
import com.example.notification.dto.response.NotificationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 알림 발송 요청 API
 *
 * POST   /notifications                      — 알림 발송 요청 등록 (즉시 발송 아님, 202 반환)
 * GET    /notifications/{id}                 — 특정 알림 상태 조회
 * GET    /users/{recipientId}/notifications  — 수신자 기준 알림 목록
 * PATCH  /notifications/{id}/read           — 읽음 처리
 * POST   /notifications/{id}/retry          — DEAD_LETTER 수동 재시도
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 알림 발송 요청 등록
     * 응답: 202 Accepted — 접수만 하고 실제 발송은 비동기 처리
     */
    @PostMapping("/notifications")
    public ResponseEntity<ApiResponse<NotificationResponse>> create(
            @Valid @RequestBody NotificationCreateRequest request) {
        NotificationResponse response = notificationService.create(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.accepted(response));
    }

    /**
     * 특정 알림 상태 조회
     */
    @GetMapping("/notifications/{id}")
    public ResponseEntity<ApiResponse<NotificationResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.findById(id)));
    }

    /**
     * 수신자 기준 알림 목록 조회
     * ?unreadOnly=true 파라미터로 읽지 않은 알림만 필터링
     */
    @GetMapping("/users/{recipientId}/notifications")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> findByRecipient(
            @PathVariable Long recipientId,
            @RequestParam(required = false) Boolean unreadOnly) {
        return ResponseEntity.ok(
            ApiResponse.ok(notificationService.findByRecipient(recipientId, unreadOnly)));
    }

    /**
     * 읽음 처리 (멱등)
     * 여러 기기에서 동시 요청이 와도 exactly-once 읽음 보장
     */
    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * DEAD_LETTER 알림 수동 재시도
     * 재시도 횟수는 0으로 초기화 (운영자가 원인 확인 후 의도적 트리거이므로)
     */
    @PostMapping("/notifications/{id}/retry")
    public ResponseEntity<ApiResponse<NotificationResponse>> manualRetry(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.manualRetry(id)));
    }
}
