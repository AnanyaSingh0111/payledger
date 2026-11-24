package in.payledger.service;

import in.payledger.audit.AuditAction;
import in.payledger.audit.AuditService;
import in.payledger.dto.MismatchDto;
import in.payledger.dto.ReconciliationReport;
import in.payledger.entity.Account;
import in.payledger.outbox.OutboxService;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks three invariants:
 *   1. the whole ledger nets to zero (every debit has a matching credit)
 *   2. every transaction's own entries net to zero, with exactly two entries
 *   3. every account's stored balance equals the sum of its ledger entries
 */
@Service
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public ReconciliationService(AccountRepository accountRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 AuditService auditService,
                                 OutboxService outboxService) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    /**
     * REPEATABLE READ on purpose (unlike money movement): every read in this transaction uses
     * one consistent snapshot, so a transfer that commits halfway through the check cannot make
     * the ledger and the balances look out of step.
     *
     * @param actorUserId the admin who triggered it, or null for the nightly job
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run(Long actorUserId) {

        Map<Long, BigDecimal> fromLedger = new HashMap<>();
        for (Object[] row : ledgerEntryRepository.netPerAccount()) {
            fromLedger.put((Long) row[0], (BigDecimal) row[1]);
        }

        List<Account> accounts = accountRepository.findAll();
        List<MismatchDto> mismatches = new ArrayList<>();
        for (Account account : accounts) {
            BigDecimal ledgerBalance = fromLedger.getOrDefault(account.getId(), BigDecimal.ZERO);
            if (account.getBalance().compareTo(ledgerBalance) != 0) {
                mismatches.add(new MismatchDto(account.getId(), account.getBalance(), ledgerBalance,
                        account.getBalance().subtract(ledgerBalance)));
            }
        }

        BigDecimal net = ledgerEntryRepository.netAll();
        boolean balanced = net.signum() == 0;
        List<Long> unbalancedTransactions = ledgerEntryRepository.unbalancedTransactionIds();
        long entries = ledgerEntryRepository.count();
        boolean healthy = balanced && mismatches.isEmpty() && unbalancedTransactions.isEmpty();

        ReconciliationReport report = new ReconciliationReport(LocalDateTime.now(), accounts.size(), entries,
                net, balanced, healthy, mismatches, unbalancedTransactions);

        auditService.record(actorUserId, AuditAction.RECONCILIATION_RUN, "SYSTEM", null,
                (healthy ? "healthy" : "UNHEALTHY") + ": accounts=" + accounts.size()
                        + " mismatches=" + mismatches.size() + " unbalancedTx=" + unbalancedTransactions.size()
                        + " ledgerNet=" + net.toPlainString());

        if (!healthy) {
            // alerting goes through the outbox like every other event
            outboxService.enqueue("SYSTEM", "reconciliation", "ReconciliationMismatch", Map.of(
                    "ledgerNet", net.toPlainString(),
                    "mismatchedAccounts", mismatches.stream().map(MismatchDto::accountId).toList(),
                    "unbalancedTransactions", unbalancedTransactions));
        }
        return report;
    }
}
