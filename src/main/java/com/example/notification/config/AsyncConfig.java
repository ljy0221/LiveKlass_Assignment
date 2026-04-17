package com.example.notification.config;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 *
 * 설계 결정:
 * 1. 알림 전용 스레드 풀을 분리해 웹 요청 처리에 영향 없도록 격리
 * 2. CallerRunsPolicy → 풀이 포화될 때 호출자 스레드에서 실행(백프레셔)
 *    운영 환경에서는 Kafka 등 외부 큐로 전환 시 이 정책은 자연히 사라짐
 * 3. ShedLock → 다중 인스턴스 환경에서 스케줄러가 동시에 실행되지 않도록 DB 락 적용
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
@RequiredArgsConstructor
public class AsyncConfig {

    private final NotificationProperties props;

    /**
     * ShedLock LockProvider — JDBC(H2/PostgreSQL/MySQL 공통)
     * shedlock 테이블은 DataInitializer에서 생성.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        var p = props.getAsync();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(p.getCorePoolSize());
        executor.setMaxPoolSize(p.getMaxPoolSize());
        executor.setQueueCapacity(p.getQueueCapacity());
        executor.setThreadNamePrefix(p.getThreadNamePrefix());
        // CallerRunsPolicy: 풀 포화 시 발행자 스레드(HTTP 요청 스레드)에서 직접 실행.
        // 작업을 버리는 대신 자연스러운 백프레셔로 PENDING 알림 유실을 방지한다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
