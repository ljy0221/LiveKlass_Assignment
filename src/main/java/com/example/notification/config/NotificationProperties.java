package com.example.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private Async async = new Async();
    private Retry retry = new Retry();
    private StuckRecovery stuckRecovery = new StuckRecovery();
    private Scheduler scheduler = new Scheduler();

    @Getter @Setter
    public static class Async {
        private int corePoolSize    = 5;
        private int maxPoolSize     = 20;
        private int queueCapacity   = 100;
        private String threadNamePrefix = "notification-";
    }

    @Getter @Setter
    public static class Retry {
        private int maxAttempts           = 3;
        private long initialDelaySeconds  = 60;
        private double multiplier         = 2.0;
    }

    @Getter @Setter
    public static class StuckRecovery {
        private int thresholdMinutes = 10;
        private long fixRateMs       = 600_000L;
    }

    @Getter @Setter
    public static class Scheduler {
        private long fixRateMs = 60_000L;
    }
}
