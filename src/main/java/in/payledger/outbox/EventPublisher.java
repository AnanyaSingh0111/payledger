package in.payledger.outbox;

import in.payledger.entity.OutboxEvent;

/**
 * Where outbox events go. The default implementation logs them; swapping in Kafka, SNS or an
 * email/SMS sender only means providing another bean of this type.
 * Implementations must throw on failure so the relay can retry.
 */
public interface EventPublisher {

    void publish(OutboxEvent event) throws Exception;
}
