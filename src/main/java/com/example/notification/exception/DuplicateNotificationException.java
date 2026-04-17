package com.example.notification.exception;

public class DuplicateNotificationException extends RuntimeException {
    public DuplicateNotificationException(String idempotencyKey) {
        super("중복 알림 요청입니다. key=" + idempotencyKey);
    }
}
