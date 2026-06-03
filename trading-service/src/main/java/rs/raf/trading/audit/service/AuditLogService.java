package rs.raf.trading.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.audit.dto.AuditLogDto;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.model.AuditLog;
import rs.raf.trading.audit.repository.AuditLogRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * B7 — Audit log servis u trading-service domenu
 * (port iz main PR #86; identitet aktera (ime/prezime) resolve-uje preko
 * {@link BankaCoreClient#getUserById} jer Employee/Client tabele zive u
 * banka-core domenu — trading-service ih nema lokalno).
 *
 * {@code record()} koristi {@link Propagation#REQUIRES_NEW} da audit upis
 * ostane cak i ako pozivajuca transakcija bude rollback-ovana.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** Maks duzina {@code description} kolone (AuditLog.java:37). */
    static final int MAX_DESCRIPTION_LENGTH = 512;

    private final AuditLogRepository auditLogRepository;
    private final BankaCoreClient bankaCoreClient;

    /**
     * Self-referenca (proxy) — afterCommit callback poziva {@code record} kroz proxy
     * da bi {@code @Transactional(REQUIRES_NEW)} zaista otvorio nezavisnu transakciju
     * (direktan {@code this.record} bi bio self-invocation i zaobisao bi proxy).
     * {@code @Lazy} prekida cirkularnu zavisnost na sebe.
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private AuditLogService self;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorType, AuditActionType action,
                       String description, String targetType, Long targetId,
                       String oldValue, String newValue) {
        AuditLog log = AuditLog.builder()
                .actorId(actorId)
                .actorType(actorType)
                .actionType(action)
                .description(truncate(description))
                .targetType(targetType)
                .targetId(targetId)
                .oldValue(oldValue)
                .newValue(newValue)
                .build();
        auditLogRepository.save(log);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorType, AuditActionType action,
                       String description, String targetType, Long targetId) {
        record(actorId, actorType, action, description, targetType, targetId, null, null);
    }

    /**
     * R1 395: skrati {@code description} na {@code length=512} pre upisa — neskracen
     * tekst baca {@code DataIntegrityViolationException} i obara ceo audit upis.
     */
    private static String truncate(String description) {
        if (description == null || description.length() <= MAX_DESCRIPTION_LENGTH) {
            return description;
        }
        return description.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
    }

    /**
     * P2-audit-coverage-1 (R4 1780 + R1 394): audit hook koji se izvrsava TEK POSLE
     * commit-a pozivajuce poslovne transakcije, i NIKAD ne obara tu transakciju.
     *
     * <p><b>R4 1780 (phantom audit fix):</b> {@link #record} je {@code REQUIRES_NEW},
     * pa se njegova nezavisna transakcija commit-uje ODMAH — ako pozivajuca (outer)
     * transakcija kasnije rollback-uje (npr. order approve padne posle audit poziva),
     * audit ostaje, a poslovna promena ne → "phantom" trag akcije koja se nije desila.
     * Registrovanjem {@link TransactionSynchronization#afterCommit()} audit se pise
     * SAMO ako outer tx zaista commit-uje. Bez aktivne tx (scheduler/no-tx put) pisemo
     * odmah (nema sta da se ceka).</p>
     *
     * <p><b>R1 394 (audit-fail ne obara akciju):</b> sam audit upis je u try/catch —
     * afterCommit hook-ovi se izvrsavaju POSLE commit-a (poslovna tx je vec trajna),
     * pa pad audit-a vise ne moze da je rollback-uje; logujemo i progutamo gresku.</p>
     */
    public void recordAfterCommit(Long actorId, String actorType, AuditActionType action,
                                  String description, String targetType, Long targetId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRecord(actorId, actorType, action, description, targetType, targetId);
                }
            });
        } else {
            // Nema aktivne transakcije (scheduler / no-tx put) — pisi odmah, best-effort.
            safeRecord(actorId, actorType, action, description, targetType, targetId);
        }
    }

    /** Best-effort audit upis (R1 394): pad audit-a se loguje i guta, ne propagira. */
    private void safeRecord(Long actorId, String actorType, AuditActionType action,
                            String description, String targetType, Long targetId) {
        try {
            // Kroz self-proxy da REQUIRES_NEW otvori nezavisnu tx (vidi #self javadoc).
            AuditLogService target = self != null ? self : this;
            target.record(actorId, actorType, action, description, targetType, targetId);
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort, afterCommit) action={} target={}/{}: {}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLogDto> query(AuditActionType actionType, Long actorId,
                                   LocalDateTime from, LocalDateTime to,
                                   Pageable pageable) {
        return auditLogRepository
                .findFiltered(actionType, actorId, from, to, pageable)
                .map(this::toDto);
    }

    /**
     * Sc45 (TODO_testovi) — filter audita po IMENU aktera (supervizora). Scenario:
     * "When unese ime supervizora u filter → prikazuju se samo akcije koje je taj
     * supervizor izvršio". trading-service nema Employee tabelu (zivi u banka-core),
     * pa ime razresavamo u skup {@code actorId}-eva preko {@link BankaCoreClient#findEmployees}
     * (isti pattern kao actuary domen) i filtriramo audit po {@code actorId IN (ids)}.
     *
     * <p>Ime se tokenizuje: ako ima razmak → prva rec {@code firstName}, ostatak
     * {@code lastName} (banka-core radi case-insensitive {@code contains} po svakom
     * polju, AND po PRISUTNIM poljima). Jedna rec (npr. samo "Milenkovic") se trazi
     * sa DVA poziva — jednom kao firstName, jednom kao lastName — pa se id-evi spoje
     * (unija, bez duplikata); slanje obe u istom pozivu bi AND-ovalo i nista ne vratilo.</p>
     *
     * <p>Ako ime ne odgovara nijednom zaposlenom → prazan {@link Page} (0 zapisa),
     * NE ceo nefiltriran log (bilo bi pogresno prikazati tudje akcije).</p>
     */
    @Transactional(readOnly = true)
    public Page<AuditLogDto> queryByActorName(AuditActionType actionType, String actorName,
                                              LocalDateTime from, LocalDateTime to,
                                              Pageable pageable) {
        List<Long> actorIds = resolveActorIdsByName(actorName);
        if (actorIds.isEmpty()) {
            // Ime ne pripada nijednom akteru — prazna strana umesto nefiltriranog loga.
            return new PageImpl<>(List.of(), pageable, 0);
        }
        return auditLogRepository
                .findFilteredByActorIds(actionType, actorIds, from, to, pageable)
                .map(this::toDto);
    }

    /**
     * Razresava ime ("Nikola Milenkovic" / "Nikola" / "Milenkovic") u skup
     * banka-core employee id-eva preko {@link BankaCoreClient#findEmployees}.
     * Na lookup pad (banka-core down) vraca praznu listu — bolje prazan rezultat
     * nego 500 ili otkrivanje celog loga.
     */
    private List<Long> resolveActorIdsByName(String actorName) {
        if (actorName == null || actorName.isBlank()) {
            return List.of();
        }
        String trimmed = actorName.trim();
        String firstName;
        String lastName;
        int sp = trimmed.indexOf(' ');
        if (sp > 0) {
            firstName = trimmed.substring(0, sp).trim();
            lastName = trimmed.substring(sp + 1).trim();
        } else {
            // Jedna rec — neka banka-core proba contains po imenu I prezimenu: dva
            // poziva pa unija id-eva (findEmployees AND-uje prisutna polja, pa ne
            // smemo poslati i firstName i lastName u istom pozivu).
            List<Long> byFirst = lookupEmployeeIds(trimmed, null);
            List<Long> byLast = lookupEmployeeIds(null, trimmed);
            return java.util.stream.Stream.concat(byFirst.stream(), byLast.stream())
                    .distinct()
                    .toList();
        }
        return lookupEmployeeIds(firstName, lastName);
    }

    private List<Long> lookupEmployeeIds(String firstName, String lastName) {
        try {
            return bankaCoreClient.findEmployees(firstName, lastName, null, null)
                    .stream()
                    .map(InternalUserDto::userId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Audit actorName lookup failed (firstName={}, lastName={}): {}",
                    firstName, lastName, ex.getMessage());
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public Optional<AuditLogDto> findById(Long id) {
        return auditLogRepository.findById(id).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<AuditLogDto> findByResource(String targetType, Long targetId) {
        return auditLogRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private AuditLogDto toDto(AuditLog log) {
        String actorName = resolveActorName(log.getActorId(), log.getActorType());
        return AuditLogDto.builder()
                .id(log.getId())
                .actorId(log.getActorId())
                .actorType(log.getActorType())
                .actorName(actorName)
                .actionType(log.getActionType().name())
                .description(log.getDescription())
                .targetType(log.getTargetType())
                .targetId(log.getTargetId())
                .oldValue(log.getOldValue())
                .newValue(log.getNewValue())
                .createdAt(log.getCreatedAt())
                .build();
    }

    private String resolveActorName(Long actorId, String actorType) {
        if (actorId == null || actorId == 0L) {
            return "ID:" + actorId;
        }
        try {
            String role = "EMPLOYEE".equals(actorType) ? UserRole.EMPLOYEE : UserRole.CLIENT;
            InternalUserDto user = bankaCoreClient.getUserById(role, actorId);
            if (user != null && user.firstName() != null && user.lastName() != null) {
                return user.firstName() + " " + user.lastName();
            }
        } catch (RuntimeException ignored) {
            // Banka-core lookup failed — fallback below.
        }
        return "ID:" + actorId;
    }
}
