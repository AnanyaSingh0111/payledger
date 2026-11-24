package in.payledger.service;

import in.payledger.dto.TransactionFilter;
import in.payledger.entity.Account;
import in.payledger.entity.Transaction;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Builds the WHERE clause for transaction history from optional filters. */
final class TransactionSpecifications {

    private TransactionSpecifications() { }

    static Specification<Transaction> matching(Long userId, TransactionFilter f) {
        return (root, query, cb) -> {
            Join<Transaction, Account> src = root.join("sourceAccount", JoinType.LEFT);
            Join<Transaction, Account> dst = root.join("destinationAccount", JoinType.LEFT);

            List<Predicate> p = new ArrayList<>();

            // visibility: the user owns at least one side of the transaction
            p.add(cb.or(
                    cb.equal(src.get("user").get("id"), userId),
                    cb.equal(dst.get("user").get("id"), userId)));

            if (f.accountId() != null) {
                p.add(cb.or(cb.equal(src.get("id"), f.accountId()), cb.equal(dst.get("id"), f.accountId())));
            }
            if (f.type() != null) p.add(cb.equal(root.get("type"), f.type()));
            if (f.status() != null) p.add(cb.equal(root.get("status"), f.status()));
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            if (f.to() != null) p.add(cb.lessThan(root.get("createdAt"), f.to()));
            if (f.minAmount() != null) p.add(cb.greaterThanOrEqualTo(root.get("amount"), f.minAmount()));
            if (f.maxAmount() != null) p.add(cb.lessThanOrEqualTo(root.get("amount"), f.maxAmount()));

            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
