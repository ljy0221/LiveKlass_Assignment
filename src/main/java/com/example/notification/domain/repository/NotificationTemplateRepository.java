package com.example.notification.domain.repository;

import com.example.notification.domain.entity.NotificationTemplate;
import com.example.notification.domain.enums.NotificationChannel;
import com.example.notification.domain.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {
    Optional<NotificationTemplate> findByTypeAndChannel(NotificationType type, NotificationChannel channel);
}
