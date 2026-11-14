package in.payledger.controller;

import in.payledger.dto.ReconciliationReport;
import in.payledger.service.ReconciliationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final ReconciliationService reconciliationService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconciliation/run")
    public ReconciliationReport runReconciliation() {
        return reconciliationService.run();
    }
}