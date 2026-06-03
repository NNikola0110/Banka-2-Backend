package rs.raf.banka2_bek.branch.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import rs.raf.banka2_bek.branch.model.Branch;
import rs.raf.banka2_bek.branch.model.BranchType;

import java.util.ArrayList;
import java.util.List;

public interface BranchRepository
        extends JpaRepository<Branch, Long>, JpaSpecificationExecutor<Branch> {

    /**
     * Vraca branches filtrirano po opcionalnim kriterijumima:
     * <ul>
     *   <li>{@code type}: BRANCH / ATM / null (svi)</li>
     *   <li>{@code has24h}: true / null (ignorisi filter)</li>
     *   <li>{@code hasDriveThrough}: true / null</li>
     *   <li>{@code search}: case-insensitive name + address LIKE / null</li>
     * </ul>
     *
     * <p><b>Faza G (live-smoke fix):</b> preneto sa JPQL {@code (:p IS NULL OR col = :p)}
     * na Criteria-API. Stari obrazac je padao na pravom PostgreSQL-u ({@code ERROR:
     * could not determine data type of parameter $N}) kada su enum {@code type} ili
     * {@code Boolean} filteri {@code null} (PG ne moze da zakljuci tip bind-a u
     * {@code :p IS NULL}). Criteria builder bind-uje tipizirane parametre → radi i na
     * PG i na H2. Potpis nepromenjen; default redosled (type ASC, name ASC) ocuvan.
     */
    default List<Branch> findByFilters(BranchType type, Boolean has24h, Boolean hasDriveThrough, String search) {
        Specification<Branch> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (has24h != null) {
                predicates.add(cb.equal(root.get("has24h"), has24h));
            }
            if (hasDriveThrough != null) {
                predicates.add(cb.equal(root.get("hasDriveThrough"), hasDriveThrough));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("address")), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return findAll(spec, Sort.by(Sort.Order.asc("type"), Sort.Order.asc("name")));
    }
}
