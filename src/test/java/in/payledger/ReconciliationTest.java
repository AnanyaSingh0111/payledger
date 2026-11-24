package in.payledger;

import in.payledger.dto.MismatchDto;
import in.payledger.dto.ReconciliationReport;
import in.payledger.entity.Account;
import in.payledger.entity.User;
import in.payledger.repository.OutboxEventRepository;
import in.payledger.service.ReconciliationService;
import in.payledger.support.IntegrationTest;
import in.payledger.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ReconciliationTest {

    @Autowired ReconciliationService reconciliationService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestData data;

    @Test
    void detectsABalanceCorruptedBehindTheLedgersBack() {
        User u = data.user();
        Account a = data.fundedAccount(u, "100.00");
        Long id = a.getId();

        // 1. a correct account is not reported
        ReconciliationReport before = reconciliationService.run(null);
        assertThat(before.mismatches()).noneMatch(m -> m.accountId().equals(id));
        assertThat(before.ledgerBalanced()).isTrue();

        long alertsBefore = outboxEventRepository.findByAggregateTypeAndAggregateId("SYSTEM", "reconciliation").size();

        // 2. corrupt the stored balance by +5 with raw SQL, the way a bug or a manual edit would
        jdbc.update("update accounts set balance = balance + 5 where id = ?", id);
        try {
            // 3. now it is reported with the exact difference, and an alert event is queued
            ReconciliationReport after = reconciliationService.run(null);
            MismatchDto found = after.mismatches().stream()
                    .filter(m -> m.accountId().equals(id)).findFirst().orElseThrow();

            assertThat(found.difference()).isEqualByComparingTo("5.00");
            assertThat(found.ledgerBalance()).isEqualByComparingTo("100.00");
            assertThat(after.healthy()).isFalse();
            assertThat(after.ledgerBalanced()).isTrue();   // the ledger itself is still fine
            assertThat(outboxEventRepository.findByAggregateTypeAndAggregateId("SYSTEM", "reconciliation"))
                    .hasSize((int) alertsBefore + 1);
        } finally {
            // put it back so other tests sharing this database see a healthy system
            jdbc.update("update accounts set balance = balance - 5 where id = ?", id);
        }
    }
}
