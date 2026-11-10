
package in.payledger.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public String health() {
        return "PayLedger is running";
    }
}