package in.payledger.service;

import in.payledger.dto.MismatchDto;
import in.payledger.dto.ReconciliationReport;
import in.payledger.entity.Account;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public ReconciliationService(AccountRepository accountRepository,
                                 LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    // One read-only transaction = one consistent snapshot, so transfers that
    // commit while the check runs cannot produce false mismatches.
    @Transactional(readOnly = true)
    public ReconciliationReport run() {

        Map<Long, BigDecimal> fromLedger = new HashMap<>();
        for (Object[] row : ledgerEntryRepository.netPerAccount()) {
            fromLedger.put((Long) row[0], (BigDecimal) row[1]);
        }

        List<Account> accounts = accountRepository.findAll();
        List<MismatchDto> mismatches = new ArrayList<>();

        for (Account account : accounts) {
            BigDecimal ledgerBalance =
                    fromLedger.getOrDefault(account.getId(), BigDecimal.ZERO);

            if (account.getBalance().compareTo(ledgerBalance) != 0) {
                mismatches.add(new MismatchDto(
                        account.getId(),
                        account.getBalance(),
                        ledgerBalance,
                        account.getBalance().subtract(ledgerBalance)));
            }
        }

        BigDecimal net = ledgerEntryRepository.netAll();
        boolean balanced = net.signum() == 0;

        return new ReconciliationReport(
                LocalDateTime.now(),
                accounts.size(),
                net,
                balanced,
                balanced && mismatches.isEmpty(),
                mismatches);
    }
}