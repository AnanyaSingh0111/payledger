package in.payledger.outbox;

import in.payledger.entity.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-in for a message broker or notification service: logs each event as one line. */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger("payledger.events");

    @Override
    public void publish(OutboxEvent event) {
        log.info("event id={} type={} {}={} payload={}", event.getId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId(), event.getPayload());
    }
}
