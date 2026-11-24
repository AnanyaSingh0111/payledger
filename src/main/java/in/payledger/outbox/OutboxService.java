package in.payledger.outbox;

import in.payledger.entity.OutboxEvent;
import in.payledger.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, JsonMapper jsonMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Must run inside the business transaction (MANDATORY fails fast if it does not): that is
     * the whole point of the outbox. Writing the event and the business change atomically avoids
     * the dual-write problem of "committed but never notified" or "notified but rolled back".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, Object aggregateId, String eventType, Map<String, ?> payload) {
        String json = jsonMapper.writeValueAsString(payload);
        outboxEventRepository.save(new OutboxEvent(aggregateType, String.valueOf(aggregateId), eventType, json));
    }
}
