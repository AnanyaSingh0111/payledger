package in.payledger.service;

import in.payledger.dto.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** Default: every night at 02:00. Override with app.reconciliation.cron. */
    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}")
    public void nightly() {
        try {
            ReconciliationReport report = reconciliationService.run(null);
            if (report.healthy()) {
                log.info("Reconciliation OK: {} accounts, {} entries, ledger net {}",
                        report.accountsChecked(), report.entriesChecked(), report.ledgerNet());
            } else {
                log.error("RECONCILIATION MISMATCH: ledgerNet={}, mismatched accounts={}, unbalanced transactions={}",
                        report.ledgerNet(), report.mismatches(), report.unbalancedTransactionIds());
            }
        } catch (Exception e) {
            log.error("Reconciliation job failed", e);
        }
    }
}
