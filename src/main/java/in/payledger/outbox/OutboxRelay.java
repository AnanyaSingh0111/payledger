package in.payledger.outbox;

import in.payledger.dto.OutboxStatsResponse;
import in.payledger.entity.OutboxEvent;
import in.payledger.entity.OutboxStatus;
import in.payledger.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Publishes due outbox events. Delivery is at-least-once: if the process dies after publishing
 * but before the PUBLISHED status commits, the event is sent again, so consumers de-duplicate
 * on the event id.
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       EventPublisher publisher,
                       Clock clock,
                       @Value("${app.outbox.batch-size:50}") int batchSize,
                       @Value("${app.outbox.max-attempts:8}") int maxAttempts) {
        this.outboxEventRepository = outboxEventRepository;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** @return how many events were published successfully in this batch */
    @Transactional
    public int publishDueBatch() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<OutboxEvent> batch = outboxEventRepository.claimDueBatch(now, batchSize);

        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                event.markPublished(now);
                published++;
            } catch (Exception e) {
                // exponential backoff: 2s, 4s, 8s ... capped at 10 minutes
                long delaySeconds = Math.min(600, 1L << Math.min(event.getAttempts() + 1, 20));
                event.markFailedAttempt(e.getClass().getSimpleName() + ": " + e.getMessage(),
                        now.plusSeconds(delaySeconds), maxAttempts);
                if (event.getStatus() == OutboxStatus.FAILED) {
                    log.error("Outbox event {} parked as FAILED after {} attempts", event.getId(), event.getAttempts(), e);
                } else {
                    log.warn("Outbox event {} failed (attempt {}), retrying in {}s",
                            event.getId(), event.getAttempts(), delaySeconds);
                }
            }
        }
        return published;
    }

    @Transactional(readOnly = true)
    public OutboxStatsResponse stats() {
        return new OutboxStatsResponse(
                outboxEventRepository.countByStatus(OutboxStatus.PENDING),
                outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED),
                outboxEventRepository.countByStatus(OutboxStatus.FAILED));
    }
}
