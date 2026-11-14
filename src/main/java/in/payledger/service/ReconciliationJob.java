package in.payledger.service;

import in.payledger.dto.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    // Default: every night at 02:00. Override with app.reconciliation.cron
    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}")
    public void nightly() {
        ReconciliationReport report = reconciliationService.run();

        if (report.healthy()) {
            log.info("Reconciliation OK: {} accounts checked, ledger net {}",
                    report.accountsChecked(), report.ledgerNet());
        } else {
            log.error("RECONCILIATION MISMATCH: ledgerBalanced={}, {} mismatched accounts: {}",
                    report.ledgerBalanced(), report.mismatches().size(), report.mismatches());
        }
    }
}