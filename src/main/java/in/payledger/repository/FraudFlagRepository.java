package in.payledger.repository;

import in.payledger.entity.FraudFlag;
import in.payledger.entity.FraudFlagStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, Long> {

    Page<FraudFlag> findByStatus(FraudFlagStatus status, Pageable pageable);

    List<FraudFlag> findByTransactionId(Long transactionId);
}
