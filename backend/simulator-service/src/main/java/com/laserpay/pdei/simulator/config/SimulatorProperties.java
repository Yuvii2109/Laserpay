package com.laserpay.pdei.simulator.config;

import com.laserpay.pdei.common.kafka.Topics;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for simulator-service, bound from the {@code pdei.simulator} prefix.
 *
 * <p>The defaults describe a laptop: a few hundred events a second, two concurrent runs, chaos
 * container control disabled until someone explicitly opts in. Everything that could damage a
 * running system - killing containers, deleting objects, corrupting hashes - is off or bounded by
 * default, because this service is a loaded gun pointed at the rest of the platform and that is
 * exactly what makes it useful.
 */
@ConfigurationProperties(prefix = "pdei.simulator")
public class SimulatorProperties {

    private final Emit emit = new Emit();
    private final Runs runs = new Runs();
    private final Artifacts artifacts = new Artifacts();
    private final Chaos chaos = new Chaos();
    private final Replay replay = new Replay();
    private final Cors cors = new Cors();

    public Emit getEmit() {
        return emit;
    }

    public Runs getRuns() {
        return runs;
    }

    public Artifacts getArtifacts() {
        return artifacts;
    }

    public Chaos getChaos() {
        return chaos;
    }

    public Replay getReplay() {
        return replay;
    }

    public Cors getCors() {
        return cors;
    }

    /** Publication rate and backpressure. */
    public static class Emit {

        /** Target publication rate. The token bucket paces the emitter to this. */
        private int eventsPerSecond = 200;

        /**
         * Maximum unacknowledged sends. This is the backpressure valve: the emitter blocks on a
         * semaphore rather than queueing unbounded work into the producer's accumulator, so a
         * slow broker slows the run down instead of exhausting the heap.
         */
        private int maxInFlight = 500;

        /** How often progress is flushed to Postgres and Redis, in events. */
        private int progressUpdateEvery = 250;

        /** Target topic. Always {@code pdei.raw.events.v1} outside tests. */
        private String topic = Topics.RAW_EVENTS;

        /** How long a send may take before the run is considered stalled. */
        private Duration sendTimeout = Duration.ofSeconds(30);

        public int getEventsPerSecond() {
            return eventsPerSecond;
        }

        public void setEventsPerSecond(int eventsPerSecond) {
            this.eventsPerSecond = eventsPerSecond;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
        }

        public int getProgressUpdateEvery() {
            return progressUpdateEvery;
        }

        public void setProgressUpdateEvery(int progressUpdateEvery) {
            this.progressUpdateEvery = progressUpdateEvery;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }
    }

    /** Run lifecycle. */
    public static class Runs {

        /** Concurrent runs. More than a couple just makes every run slower. */
        private int maxConcurrent = 2;

        /** TTL of {@code pdei:sim:run:{runId}} (platform contract 12). */
        private Duration redisTtl = Duration.ofHours(24);

        /** Keep the generated stream in memory after a run, so chaos can target its events. */
        private boolean retainStream = true;

        /** Cap on retained events per run, so a large run cannot pin the heap indefinitely. */
        private int retainedStreamLimit = 5_000;

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public Duration getRedisTtl() {
            return redisTtl;
        }

        public void setRedisTtl(Duration redisTtl) {
            this.redisTtl = redisTtl;
        }

        public boolean isRetainStream() {
            return retainStream;
        }

        public void setRetainStream(boolean retainStream) {
            this.retainStream = retainStream;
        }

        public int getRetainedStreamLimit() {
            return retainedStreamLimit;
        }

        public void setRetainedStreamLimit(int retainedStreamLimit) {
            this.retainedStreamLimit = retainedStreamLimit;
        }
    }

    /** Synthetic evidence bytes uploaded to MinIO. */
    public static class Artifacts {

        /**
         * Upload the generated artifacts so evidence really exists in object storage. Without
         * this, document-processor-service has nothing to extract and evidence integrity
         * verification has nothing to re-hash.
         */
        private boolean upload = true;

        /** Cap per run; a 200k-transaction benchmark should not also write a million objects. */
        private int maxUploads = 5_000;

        public boolean isUpload() {
            return upload;
        }

        public void setUpload(boolean upload) {
            this.upload = upload;
        }

        public int getMaxUploads() {
            return maxUploads;
        }

        public void setMaxUploads(int maxUploads) {
            this.maxUploads = maxUploads;
        }
    }

    /** Chaos injection. */
    public static class Chaos {

        /**
         * Allow container-level chaos (KILL_WORKER, RESTART_CONSUMER, SLOW_CONSUMER) through the
         * Docker Engine API. Off by default: it requires exposing the Docker socket, which is
         * root on the host, and nobody should get that by accident.
         */
        private boolean dockerEnabled = false;

        /** Docker Engine API base URL, e.g. {@code http://localhost:2375}. */
        private String dockerHost = "http://localhost:2375";

        private Duration dockerTimeout = Duration.ofSeconds(10);

        /** Container name prefix, so {@code kill worker=readiness} means {@code pdei-readiness}. */
        private String containerPrefix = "pdei-";

        /**
         * Redis key prefix for the documented control-directive fallback used when Docker control
         * is unavailable: {@code pdei:sim:control:{service}}.
         */
        private String controlKeyPrefix = "pdei:sim:control:";

        private Duration controlTtl = Duration.ofMinutes(5);

        /** Recently emitted events kept per run so DUPLICATE / OUT_OF_ORDER can target them. */
        private int recentEventBuffer = 2_000;

        /** Upper bound on how many events one injection may act on. */
        private int maxEventCount = 500;

        /** Upper bound on an injected delay, so a demo cannot wedge the executor for an hour. */
        private Duration maxDelay = Duration.ofMinutes(5);

        public boolean isDockerEnabled() {
            return dockerEnabled;
        }

        public void setDockerEnabled(boolean dockerEnabled) {
            this.dockerEnabled = dockerEnabled;
        }

        public String getDockerHost() {
            return dockerHost;
        }

        public void setDockerHost(String dockerHost) {
            this.dockerHost = dockerHost;
        }

        public Duration getDockerTimeout() {
            return dockerTimeout;
        }

        public void setDockerTimeout(Duration dockerTimeout) {
            this.dockerTimeout = dockerTimeout;
        }

        public String getContainerPrefix() {
            return containerPrefix;
        }

        public void setContainerPrefix(String containerPrefix) {
            this.containerPrefix = containerPrefix;
        }

        public String getControlKeyPrefix() {
            return controlKeyPrefix;
        }

        public void setControlKeyPrefix(String controlKeyPrefix) {
            this.controlKeyPrefix = controlKeyPrefix;
        }

        public Duration getControlTtl() {
            return controlTtl;
        }

        public void setControlTtl(Duration controlTtl) {
            this.controlTtl = controlTtl;
        }

        public int getRecentEventBuffer() {
            return recentEventBuffer;
        }

        public void setRecentEventBuffer(int recentEventBuffer) {
            this.recentEventBuffer = recentEventBuffer;
        }

        public int getMaxEventCount() {
            return maxEventCount;
        }

        public void setMaxEventCount(int maxEventCount) {
            this.maxEventCount = maxEventCount;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }
    }

    /** Topic replay. */
    public static class Replay {

        /** Ceiling on records read in one replay. */
        private int maxRecords = 50_000;

        /** Poll timeout; two consecutive empty polls end the replay. */
        private Duration pollTimeout = Duration.ofSeconds(2);

        /**
         * Re-publish each replayed record to its topic. This is what actually proves
         * replayability: downstream consumers see the events again and must converge on the same
         * state. Turn it off to inspect a range without disturbing anything.
         */
        private boolean republish = true;

        public int getMaxRecords() {
            return maxRecords;
        }

        public void setMaxRecords(int maxRecords) {
            this.maxRecords = maxRecords;
        }

        public Duration getPollTimeout() {
            return pollTimeout;
        }

        public void setPollTimeout(Duration pollTimeout) {
            this.pollTimeout = pollTimeout;
        }

        public boolean isRepublish() {
            return republish;
        }

        public void setRepublish(boolean republish) {
            this.republish = republish;
        }
    }

    /** Browser CORS grant for the Next.js console (platform contract 14: {@code /simulation}). */
    public static class Cors {

        /**
         * Origins allowed to call {@code /sim/**} from a browser. Bound from
         * {@code PDEI_FRONTEND_ORIGIN} in application.yml, same as api-gateway-service; the
         * default is the Next.js dev server of platform contract 2.
         */
        private List<String> allowedOrigins = List.of("http://localhost:3000");

        /** The verbs the REST surface of contract 8.5 actually uses, plus the preflight. */
        private List<String> allowedMethods = List.of("GET", "POST", "OPTIONS");

        /**
         * Off: the console fetches with {@code credentials: 'same-origin'} and the simulator has
         * no session or cookie of its own, so granting credentials would widen the surface for
         * nothing.
         */
        private boolean allowCredentials = false;

        /** How long a browser may cache a preflight result. */
        private Duration maxAge = Duration.ofHours(1);

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }
    }
}
