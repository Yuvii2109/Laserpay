package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.api.config.ApiProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Registers the stream HEARTBEAT on the interval configured by
 * {@code pdei.api.stream.heartbeat-interval}.
 *
 * <p>Programmatic registration rather than {@code @Scheduled(fixedDelayString = ...)} because
 * {@code fixedDelayString} accepts only a plain number of milliseconds or ISO-8601 text, while the
 * property is bound as a Spring Boot {@link Duration} and is naturally written {@code 15s}. Wiring
 * the two together by hand keeps one property, in the readable form, driving the actual schedule.</p>
 *
 * <p>A fixed <em>delay</em>, not a fixed rate: if a heartbeat round is slow because many subscribers
 * are connected, the next one should start after it finishes rather than pile up behind it.</p>
 */
@Configuration(proxyBeanMethods = false)
public class StreamSchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StreamSchedulingConfig.class);

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);

    private final StreamHub hub;
    private final ApiProperties properties;

    public StreamSchedulingConfig(StreamHub hub, ApiProperties properties) {
        this.hub = hub;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        Duration interval = properties.getStream().getHeartbeatInterval();
        if (interval == null || interval.compareTo(MIN_INTERVAL) < 0) {
            log.warn("Heartbeat interval {} is below the {} floor; using {}",
                    interval, MIN_INTERVAL, DEFAULT_INTERVAL);
            interval = DEFAULT_INTERVAL;
        }
        registrar.addFixedDelayTask(hub::heartbeat, interval);
        log.info("Control-tower heartbeat scheduled every {}", interval);
    }
}
