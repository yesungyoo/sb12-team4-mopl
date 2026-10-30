package com.mopl.content.search.kafka;

import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.Dependency;
import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.FailureClassification;
import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.FailureType;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Tracks only explicitly classified Elasticsearch and MySQL transient pauses.
 * Listener/Recoverer wiring is intentionally kept separate from this coordinator.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class ContentSearchKafkaAutoResumeCoordinator {

    private static final long PAUSE_REQUEST_TIMEOUT_MULTIPLIER = 3L;

    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final Map<Dependency, ContentSearchDependencyHealthProbe> probes;
    private final ProbeSettings settings;
    private final Clock clock;
    private final ProbeInvoker probeInvoker;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock probeCycleLock = new ReentrantLock();
    private final Map<PauseKey, PauseState> pausedPartitions = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public ContentSearchKafkaAutoResumeCoordinator(
            KafkaListenerEndpointRegistry listenerRegistry,
            List<ContentSearchDependencyHealthProbe> probes,
            @Value("${mopl.kafka.auto-resume.probe-interval-ms:10000}") long probeIntervalMs,
            @Value("${mopl.kafka.auto-resume.probe-timeout-ms:2000}") long probeTimeoutMs,
            @Value("${mopl.kafka.auto-resume.consecutive-successes:3}") int consecutiveSuccesses,
            @Value("${mopl.kafka.auto-resume.minimum-pause-ms:10000}") long minimumPauseMs
    ) {
        this(listenerRegistry, probes,
                new ProbeSettings(probeIntervalMs, probeTimeoutMs, consecutiveSuccesses, minimumPauseMs),
                Clock.systemUTC(), new BoundedProbeInvoker());
    }

    ContentSearchKafkaAutoResumeCoordinator(
            KafkaListenerEndpointRegistry listenerRegistry,
            List<ContentSearchDependencyHealthProbe> probes,
            ProbeSettings settings,
            Clock clock,
            ProbeInvoker probeInvoker
    ) {
        this.listenerRegistry = listenerRegistry;
        this.settings = settings;
        this.clock = clock;
        this.probeInvoker = probeInvoker;
        settings.validate();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "content-kafka-auto-resume");
            thread.setDaemon(true);
            return thread;
        });
        this.probes = new EnumMap<>(Dependency.class);
        for (ContentSearchDependencyHealthProbe probe : probes) {
            if (probe.dependency() != Dependency.ELASTICSEARCH && probe.dependency() != Dependency.MYSQL) {
                throw new IllegalArgumentException("자동 복구 Probe는 ES와 MySQL만 등록할 수 있습니다.");
            }
            if (this.probes.put(probe.dependency(), probe) != null) {
                throw new IllegalArgumentException("의존성별 Probe는 하나만 등록할 수 있습니다: " + probe.dependency());
            }
        }
    }

    /**
     * Records a new failure. Calling this for a non-eligible failure invalidates
     * any pending automatic-resume state for the same listener partition.
     */
    public void recordFailure(
            String listenerId,
            TopicPartition partition,
            long failedOffset,
            PauseReason pauseReason,
            FailureClassification classification
    ) {
        PauseKey key = new PauseKey(listenerId, partition);
        if (pauseReason != PauseReason.COMMON_TRANSIENT
                || !isEligible(classification)
                || !probes.containsKey(classification.dependency())) {
            cancel(listenerId, partition);
            return;
        }

        PauseState replacement = new PauseState(
                failedOffset,
                classification.dependency(),
                clock.millis()
        );
        PauseState previous = pausedPartitions.get(key);
        if (previous == null) {
            pausedPartitions.put(key, replacement);
        } else {
            synchronized (previous) {
                if (pausedPartitions.get(key) == previous) {
                    replacement.pauseOwners.addAll(previous.pauseOwners);
                    if (previous.owner != null
                            && (previous.owner.isPartitionPaused(partition)
                            || previous.owner.isPartitionPauseRequested(partition))) {
                        replacement.pauseOwners.add(previous.owner);
                    }
                    pausedPartitions.replace(key, previous, replacement);
                } else {
                    pausedPartitions.put(key, replacement);
                }
            }
        }
        log.info(
                "Kafka 자동 복구 대상을 등록했습니다. listener={}, topic={}, partition={}, offset={}, dependency={}",
                listenerId, partition.topic(), partition.partition(), failedOffset, classification.dependency()
        );
    }

    public void cancel(String listenerId, TopicPartition partition) {
        PauseKey key = new PauseKey(listenerId, partition);
        PauseState state = pausedPartitions.get(key);
        if (state != null) {
            synchronized (state) {
                pausedPartitions.remove(key, state);
            }
        }
    }

    /** Detach the old owner while retaining only an already-eligible failure for reassignment. */
    public void onPartitionsRevoked(String listenerId, Collection<TopicPartition> partitions) {
        partitions.forEach(partition -> {
            rememberPauseOwner(listenerId, partition);
            detachForReassignment(listenerId, partition);
        });
    }

    /** Re-arm retained eligible state for the new assignment; old probe successes are discarded. */
    public void onPartitionsAssigned(String listenerId, Collection<TopicPartition> partitions) {
        long now = clock.millis();
        for (TopicPartition partition : partitions) {
            PauseKey key = new PauseKey(listenerId, partition);
            PauseState state = pausedPartitions.get(key);
            if (state != null) {
                replaceWithFreshState(key, state, now, false, null);
            }
        }

        MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
        Collection<TopicPartition> assigned = container == null ? null : container.getAssignedPartitions();
        if (assigned != null) {
            for (Map.Entry<PauseKey, PauseState> entry : pausedPartitions.entrySet()) {
                PauseKey key = entry.getKey();
                PauseState state = entry.getValue();
                if (key.listenerId().equals(listenerId) && !assigned.contains(key.partition())) {
                    synchronized (state) {
                        if (pausedPartitions.get(key) == state && state.awaitingAssignment) {
                            pausedPartitions.remove(key, state);
                        }
                    }
                }
            }
        }
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::runScheduledProbeCycle,
                settings.probeIntervalMs(),
                settings.probeIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    public void probePausedPartitions() {
        if (!probeCycleLock.tryLock()) {
            return;
        }
        try {
            Map<Dependency, List<TrackedPause>> ready = collectReadyPartitions();
            for (Map.Entry<Dependency, List<TrackedPause>> entry : ready.entrySet()) {
                boolean healthy = runProbe(entry.getKey());
                for (TrackedPause tracked : entry.getValue()) {
                    applyProbeResult(tracked, healthy);
                }
            }
        } finally {
            probeCycleLock.unlock();
        }
    }

    private void runScheduledProbeCycle() {
        try {
            probePausedPartitions();
        } catch (RuntimeException failure) {
            log.error("Kafka 자동 복구 Probe 주기를 완료하지 못했습니다.", failure);
        }
    }

    public int trackedPauseCount() {
        return pausedPartitions.size();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        probeInvoker.close();
    }

    private Map<Dependency, List<TrackedPause>> collectReadyPartitions() {
        Map<Dependency, List<TrackedPause>> ready = new EnumMap<>(Dependency.class);
        long now = clock.millis();
        for (Map.Entry<PauseKey, PauseState> entry : pausedPartitions.entrySet()) {
            PauseKey key = entry.getKey();
            PauseState state = entry.getValue();
            MessageListenerContainer container = listenerRegistry.getListenerContainer(key.listenerId());
            if (container == null || !container.isRunning()) {
                pausedPartitions.remove(key, state);
                continue;
            }

            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            if (assigned == null || !assigned.contains(key.partition())) {
                synchronized (state) {
                    if (pausedPartitions.get(key) == state && !state.awaitingAssignment) {
                        pausedPartitions.remove(key, state);
                    }
                }
                continue;
            }

            MessageListenerContainer owner = currentPartitionOwner(container, key.partition());
            if (!owner.isRunning()) {
                replaceWithFreshState(key, state, now, true, null);
                continue;
            }

            synchronized (state) {
                if (pausedPartitions.get(key) != state) {
                    continue;
                }
                if (state.awaitingAssignment) {
                    state.awaitingAssignment = false;
                    state.owner = null;
                    state.phase = PausePhase.PAUSE_REQUESTED;
                    state.pausedAtMillis = now;
                    state.consecutiveSuccesses = 0;
                }
                if (owner.isPartitionPaused(key.partition())
                        || owner.isPartitionPauseRequested(key.partition())) {
                    state.pauseOwners.add(owner);
                }
                if (state.owner != null && state.owner != owner) {
                    // Keep the known eligible dependency, but discard the former owner's pause/probe state.
                    replaceWithFreshState(key, state, now, false, owner);
                    continue;
                }
                state.owner = owner;

                if (state.phase == PausePhase.RESUME_REQUESTED) {
                    if (!owner.isPartitionPaused(key.partition())) {
                        if (hasOutstandingPauseOwner(state, key.partition(), owner)) {
                            replaceWithFreshState(key, state, now, false, null);
                        } else {
                            pausedPartitions.remove(key, state);
                            log.info(
                                    "Kafka 자동 Resume이 반영되어 복구 상태를 정리했습니다. listener={}, topic={}, partition={}, offset={}",
                                    key.listenerId(), key.partition().topic(), key.partition().partition(), state.failedOffset
                            );
                        }
                    }
                    continue;
                }

                if (!owner.isPartitionPaused(key.partition())) {
                    if (owner.isPartitionPauseRequested(key.partition())) {
                        state.phase = PausePhase.PAUSE_REQUESTED;
                    } else if (hasOutstandingPauseOwner(state, key.partition(), owner)) {
                        // A former child still owns a persistent pause request; retain eligibility until it is reassigned.
                        continue;
                    } else if (now - state.createdAtMillis
                            > settings.probeIntervalMs() * PAUSE_REQUEST_TIMEOUT_MULTIPLIER) {
                        pausedPartitions.remove(key, state);
                    }
                    continue;
                }

                if (state.phase != PausePhase.PAUSED) {
                    state.pausedAtMillis = now;
                }
                state.phase = PausePhase.PAUSED;
                if (now - state.pausedAtMillis >= settings.minimumPauseMs()) {
                    ready.computeIfAbsent(state.dependency, ignored -> new ArrayList<>())
                            .add(new TrackedPause(key, state, container, owner));
                }
            }
        }
        return ready;
    }

    private boolean runProbe(Dependency dependency) {
        ContentSearchDependencyHealthProbe probe = probes.get(dependency);
        if (probe == null) {
            return false;
        }
        boolean healthy = probeInvoker.check(probe, settings.probeTimeoutMs());
        if (healthy) {
            log.debug("Kafka 자동 복구 Probe 성공. dependency={}", dependency);
        } else {
            log.warn("Kafka 자동 복구 Probe 실패 또는 timeout. dependency={}", dependency);
        }
        return healthy;
    }

    private static String causeTypes(Throwable failure) {
        StringJoiner causeTypes = new StringJoiner(" -> ");
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            causeTypes.add(current.getClass().getName());
            current = current.getCause();
        }
        return causeTypes.toString();
    }

    private void applyProbeResult(TrackedPause tracked, boolean healthy) {
        PauseKey key = tracked.key();
        PauseState state = tracked.state();
        synchronized (state) {
            if (pausedPartitions.get(key) != state || state.phase != PausePhase.PAUSED) {
                return;
            }
            if (!isCurrentAssignment(tracked)) {
                rebindAfterStaleProbe(tracked);
                return;
            }
            if (!healthy) {
                state.consecutiveSuccesses = 0;
                return;
            }

            state.consecutiveSuccesses++;
            if (state.consecutiveSuccesses < settings.consecutiveSuccesses()) {
                return;
            }

            if (!isCurrentAssignment(tracked)) {
                rebindAfterStaleProbe(tracked);
                return;
            }

            try {
                tracked.owner().resumePartition(key.partition());
                state.phase = PausePhase.RESUME_REQUESTED;
                state.consecutiveSuccesses = 0;
                log.info(
                        "Kafka 자동 Resume을 요청했습니다. listener={}, topic={}, partition={}, offset={}, dependency={}",
                        key.listenerId(), key.partition().topic(), key.partition().partition(),
                        state.failedOffset, state.dependency
                );
            } catch (RuntimeException resumeFailure) {
                state.consecutiveSuccesses = 0;
                log.error(
                        "Kafka 자동 Resume 요청에 실패했습니다. listener={}, topic={}, partition={}, offset={}",
                        key.listenerId(), key.partition().topic(), key.partition().partition(),
                        state.failedOffset, resumeFailure
                );
            }
        }
    }

    private boolean isCurrentAssignment(TrackedPause tracked) {
        MessageListenerContainer current = listenerRegistry.getListenerContainer(tracked.key().listenerId());
        if (current == null || current != tracked.container() || !current.isRunning()) {
            return false;
        }
        Collection<TopicPartition> assigned = current.getAssignedPartitions();
        if (assigned == null || !assigned.contains(tracked.key().partition())) {
            return false;
        }
        MessageListenerContainer owner = currentPartitionOwner(current, tracked.key().partition());
        if (owner != tracked.owner() || !owner.isRunning()) {
            return false;
        }
        Collection<TopicPartition> ownerAssignments = owner.getAssignedPartitions();
        return ownerAssignments != null
                && ownerAssignments.contains(tracked.key().partition())
                && owner.isPartitionPaused(tracked.key().partition());
    }

    private MessageListenerContainer currentPartitionOwner(
            MessageListenerContainer container,
            TopicPartition partition
    ) {
        MessageListenerContainer owner = container.getContainerFor(partition.topic(), partition.partition());
        return owner == null ? container : owner;
    }

    private void detachForReassignment(String listenerId, TopicPartition partition) {
        PauseKey key = new PauseKey(listenerId, partition);
        PauseState state = pausedPartitions.get(key);
        if (state != null) {
            replaceWithFreshState(key, state, clock.millis(), true, null);
        }
    }

    private void rememberPauseOwner(String listenerId, TopicPartition partition) {
        PauseKey key = new PauseKey(listenerId, partition);
        PauseState state = pausedPartitions.get(key);
        MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
        if (state == null || container == null) {
            return;
        }
        MessageListenerContainer owner = currentPartitionOwner(container, partition);
        if (owner.isPartitionPaused(partition) || owner.isPartitionPauseRequested(partition)) {
            synchronized (state) {
                if (pausedPartitions.get(key) == state) {
                    state.pauseOwners.add(owner);
                }
            }
        }
    }

    private boolean hasOutstandingPauseOwner(
            PauseState state,
            TopicPartition partition,
            MessageListenerContainer currentOwner
    ) {
        return state.pauseOwners.stream()
                .anyMatch(owner -> owner != currentOwner
                        && owner.isRunning()
                        && owner.isPartitionPauseRequested(partition));
    }

    private void rebindAfterStaleProbe(TrackedPause tracked) {
        PauseKey key = tracked.key();
        PauseState state = tracked.state();
        MessageListenerContainer current = listenerRegistry.getListenerContainer(key.listenerId());
        if (current == null || current != tracked.container() || !current.isRunning()) {
            pausedPartitions.remove(key, state);
            return;
        }
        Collection<TopicPartition> assigned = current.getAssignedPartitions();
        if (assigned == null || !assigned.contains(key.partition())) {
            pausedPartitions.remove(key, state);
            return;
        }
        MessageListenerContainer owner = currentPartitionOwner(current, key.partition());
        Collection<TopicPartition> ownerAssignments = owner.getAssignedPartitions();
        boolean stillPaused = owner.isRunning()
                && ownerAssignments != null
                && ownerAssignments.contains(key.partition())
                && (owner.isPartitionPaused(key.partition()) || owner.isPartitionPauseRequested(key.partition()));
        if (!stillPaused) {
            pausedPartitions.remove(key, state);
            return;
        }
        replaceWithFreshState(key, state, clock.millis(), false, owner);
    }

    private void replaceWithFreshState(
            PauseKey key,
            PauseState previous,
            long now,
            boolean awaitingAssignment,
            MessageListenerContainer owner
    ) {
        synchronized (previous) {
            if (pausedPartitions.get(key) != previous) {
                return;
            }
            PauseState replacement = new PauseState(
                    previous.failedOffset,
                    previous.dependency,
                    now,
                    awaitingAssignment,
                    owner
            );
            replacement.pauseOwners.addAll(previous.pauseOwners);
            if (previous.owner != null) {
                replacement.pauseOwners.add(previous.owner);
            }
            if (owner != null && (owner.isPartitionPaused(key.partition())
                    || owner.isPartitionPauseRequested(key.partition()))) {
                replacement.pauseOwners.add(owner);
            }
            pausedPartitions.replace(key, previous, replacement);
        }
    }

    private boolean isEligible(FailureClassification classification) {
        return classification != null
                && classification.failureType() == FailureType.COMMON_TRANSIENT
                && (classification.dependency() == Dependency.ELASTICSEARCH
                || classification.dependency() == Dependency.MYSQL);
    }

    private record PauseKey(String listenerId, TopicPartition partition) {
    }

    private record TrackedPause(
            PauseKey key,
            PauseState state,
            MessageListenerContainer container,
            MessageListenerContainer owner
    ) {
    }

    private static final class PauseState {

        private final long failedOffset;
        private final Dependency dependency;
        private final long createdAtMillis;
        private volatile MessageListenerContainer owner;
        private volatile boolean awaitingAssignment;
        private final Set<MessageListenerContainer> pauseOwners =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private PausePhase phase = PausePhase.PAUSE_REQUESTED;
        private long pausedAtMillis;
        private int consecutiveSuccesses;

        private PauseState(long failedOffset, Dependency dependency, long createdAtMillis) {
            this(failedOffset, dependency, createdAtMillis, false, null);
        }

        private PauseState(
                long failedOffset,
                Dependency dependency,
                long createdAtMillis,
                boolean awaitingAssignment,
                MessageListenerContainer owner
        ) {
            this.failedOffset = failedOffset;
            this.dependency = dependency;
            this.createdAtMillis = createdAtMillis;
            this.pausedAtMillis = createdAtMillis;
            this.awaitingAssignment = awaitingAssignment;
            this.owner = owner;
        }
    }

    private enum PausePhase {
        PAUSE_REQUESTED,
        PAUSED,
        RESUME_REQUESTED
    }

    public enum PauseReason {
        COMMON_TRANSIENT,
        CONFIGURATION,
        AMBIGUOUS,
        AI_UNAVAILABLE,
        DLT_PUBLISH_FAILURE,
        UNKNOWN
    }

    record ProbeSettings(long probeIntervalMs, long probeTimeoutMs, int consecutiveSuccesses, long minimumPauseMs) {

        void validate() {
            if (probeIntervalMs <= 0 || probeTimeoutMs <= 0 || consecutiveSuccesses <= 0 || minimumPauseMs < 0) {
                throw new IllegalArgumentException("Kafka 자동 복구 Probe 설정값이 유효하지 않습니다.");
            }
        }
    }

    @FunctionalInterface
    interface ProbeInvoker extends AutoCloseable {

        boolean check(ContentSearchDependencyHealthProbe probe, long timeoutMs);

        @Override
        default void close() {
        }
    }

    static final class BoundedProbeInvoker implements ProbeInvoker {

        private final ThreadPoolExecutor executor;
        private final Map<Dependency, ProbeTask> inFlight = new ConcurrentHashMap<>();

        BoundedProbeInvoker() {
            this(2, 2);
        }

        BoundedProbeInvoker(int workerCount, int queueCapacity) {
            AtomicInteger threadNumber = new AtomicInteger();
            ThreadFactory threadFactory = task -> {
                Thread thread = new Thread(task, "content-kafka-health-probe-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
            this.executor = new ThreadPoolExecutor(
                    workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity), threadFactory,
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }

        @Override
        public boolean check(ContentSearchDependencyHealthProbe probe, long timeoutMs) {
            ProbeTask task = new ProbeTask(probe, inFlight);
            if (inFlight.putIfAbsent(probe.dependency(), task) != null) {
                log.debug("Kafka 자동 복구 Probe를 건너뜁니다. 이미 실행 중입니다. dependency={}, probe={}",
                        probe.dependency(), probe.getClass().getName());
                return false;
            }
            try {
                executor.execute(task);
            } catch (RejectedExecutionException rejected) {
                task.cancelBeforeStart();
                log.warn("Kafka 자동 복구 Probe 작업이 거부되었습니다. dependency={}, probe={}, causeTypes={}",
                        probe.dependency(), probe.getClass().getName(), causeTypes(rejected));
                return false;
            }
            try {
                return task.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                task.cancelProbe();
                log.warn("Kafka 자동 복구 Probe가 중단되었습니다. dependency={}, probe={}, causeTypes={}",
                        probe.dependency(), probe.getClass().getName(), causeTypes(interrupted));
                return false;
            } catch (TimeoutException timeout) {
                task.cancelProbe();
                log.warn("Kafka 자동 복구 Probe timeout. dependency={}, probe={}, timeoutMs={}, causeTypes={}",
                        probe.dependency(), probe.getClass().getName(), timeoutMs, causeTypes(timeout));
                return false;
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (cause instanceof ContentSearchDependencyHealthProbe.HealthCheckFailedException healthFailure) {
                    log.warn("Kafka 자동 복구 Health Check가 비정상 결과를 반환했습니다. dependency={}, probe={}, reasonCode={}, causeTypes={}",
                            probe.dependency(), probe.getClass().getName(), healthFailure.reasonCode(), causeTypes(cause));
                } else {
                    log.warn("Kafka 자동 복구 Probe에서 예외가 발생했습니다. dependency={}, probe={}, causeTypes={}",
                            probe.dependency(), probe.getClass().getName(), causeTypes(cause));
                }
                return false;
            } catch (RuntimeException failure) {
                task.cancelProbe();
                log.warn("Kafka 자동 복구 Probe 실행에 실패했습니다. dependency={}, probe={}, causeTypes={}",
                        probe.dependency(), probe.getClass().getName(), causeTypes(failure));
                return false;
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        private enum ProbeTaskState {
            SCHEDULED,
            RUNNING,
            COMPLETED,
            CANCELLED_BEFORE_START,
            CANCELLED_RUNNING
        }

        private static final class ProbeTask extends FutureTask<Boolean> {

            private final Dependency dependency;
            private final Map<Dependency, ProbeTask> inFlight;
            private final AtomicReference<ProbeTaskState> state =
                    new AtomicReference<>(ProbeTaskState.SCHEDULED);

            private ProbeTask(
                    ContentSearchDependencyHealthProbe probe,
                    Map<Dependency, ProbeTask> inFlight
            ) {
                super(() -> {
                    probe.checkHealthy();
                    return true;
                });
                this.dependency = probe.dependency();
                this.inFlight = inFlight;
            }

            @Override
            public void run() {
                if (!state.compareAndSet(ProbeTaskState.SCHEDULED, ProbeTaskState.RUNNING)) {
                    return;
                }
                try {
                    super.run();
                } finally {
                    state.set(ProbeTaskState.COMPLETED);
                    inFlight.remove(dependency, this);
                }
            }

            private void cancelProbe() {
                while (true) {
                    ProbeTaskState current = state.get();
                    if (current == ProbeTaskState.SCHEDULED) {
                        if (state.compareAndSet(current, ProbeTaskState.CANCELLED_BEFORE_START)) {
                            cancel(false);
                            inFlight.remove(dependency, this);
                            return;
                        }
                    } else if (current == ProbeTaskState.RUNNING) {
                        if (state.compareAndSet(current, ProbeTaskState.CANCELLED_RUNNING)) {
                            cancel(true);
                            return;
                        }
                    } else {
                        return;
                    }
                }
            }

            private void cancelBeforeStart() {
                if (state.compareAndSet(ProbeTaskState.SCHEDULED, ProbeTaskState.CANCELLED_BEFORE_START)) {
                    cancel(false);
                    inFlight.remove(dependency, this);
                }
            }
        }
    }
}
