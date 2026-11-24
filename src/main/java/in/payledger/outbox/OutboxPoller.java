package in.payledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRelay relay;

    public OutboxPoller(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void poll() {
        try {
            // drain the backlog in batches, but stop as soon as a batch publishes nothing
            while (relay.publishDueBatch() > 0) {
                // keep going
            }
        } catch (Exception e) {
            log.error("Outbox poll failed; will retry on the next tick", e);
        }
    }
}
