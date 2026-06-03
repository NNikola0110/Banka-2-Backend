package rs.raf.trading.otc.saga.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.service.OtcAccessPolicy;
import rs.raf.trading.otc.saga.fault.SagaFaultInjector;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaLogEntry;
import rs.raf.trading.otc.saga.model.SagaPhase;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.model.SagaStepKind;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.util.ListingCurrencyResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Model B SAGA orchestrator za iskoriscavanje intra-bank OTC opcionog ugovora.
 *
 * <p>Pre-saga validacija baca 4xx-mapirane izuzetke BEZ kreiranja {@code
 * saga_logs} reda i BEZ bocnih efekata (404 nepostojeci ugovor, 403 ne-kupac,
 * 409 nevazeci status / istekao settlement). Posle perzistencije {@link
 * SagaLog} (RUNNING) svaka greska forward faze vodi u kompenzaciju (obrnuti,
 * idempotentni C{n-1}..C1, retry do uspeha), pa COMPENSATED; uspeh svih faza
 * -> COMPLETED.
 *
 * <p>Sopstveni je {@code @Service} (ne menja {@code OtcService} ctor), pa
 * {@code OtcServiceSagaTest} ostaje zelen.
 *
 * <p>Sve forward faze (F1..F5, {@link #ALL_STEPS}) i njihovi obrnuti kompenzatori
 * (C5..C1) su potpuno implementirani — konzervacija sredstava dokazana
 * (P0-1: granularni {@code f3CommitDone/f3CreditDone/f4SellerApplied} flag-ovi na
 * {@link SagaLog}, partial-aware C3/C4). Pored faza, ovde zivi i kontrolni tok
 * (validacija + lifecycle loga + forward/compensation petlje).
 */
@Slf4j
@Service
public class OtcExerciseSagaOrchestrator {

    private final OtcContractRepository contractRepository;
    private final PortfolioRepository portfolioRepository;
    private final SagaLogRepository sagaLogRepository;
    private final SagaLogWriter sagaLogWriter;
    private final BankaCoreClient bankaCoreClient;
    private final CurrencyConversionService currencyConversionService;
    private final TradingUserResolver userResolver;
    private final SagaFaultInjector fault;

    /** Sve forward faze (1..5) — recovery COMPLETED detekcija (crash posle F5). */
    private static final Set<Integer> ALL_STEPS = Set.of(1, 2, 3, 4, 5);

    /**
     * R1 785 / R2 1411: gornja granica inline-retry pokusaja jednog kompenzatora u
     * {@link #compensate}. Kad se dostigne, saga ostaje {@code COMPENSATING}
     * (durabilno) i {@code SagaRecoveryService} sweep nastavlja retry (vidi
     * obrazlozenje u {@code compensate}). Ranije hardkodirano kao {@code 5}.
     */
    private static final int MAX_INLINE_COMPENSATE_ATTEMPTS = 5;

    /**
     * <b>N4 (P0-T2):</b> deterministicki idempotency kljuc F3 {@code creditFunds} poziva
     * (isplata prodavcu). Izveden iskljucivo iz {@code sagaId}, pa ga i live F3 (forward) i
     * recovery C3 (rebuild) rekonstruisu IDENTICNO — recovery time moze AUTORITATIVNO da pita
     * banka-core da li je credit VEC konzumiran ({@code GET /internal/funds/idempotency/{key}}).
     * MORA se poklapati sa kljucem koriscenim u {@link #f3TransferFunds} {@code creditFunds} pozivu.
     */
    public static String f3CreditIdempotencyKey(String sagaId) {
        return "otc-saga-" + sagaId + "-f3-credit";
    }

    public OtcExerciseSagaOrchestrator(OtcContractRepository contractRepository,
                                       PortfolioRepository portfolioRepository,
                                       SagaLogRepository sagaLogRepository,
                                       SagaLogWriter sagaLogWriter,
                                       BankaCoreClient bankaCoreClient,
                                       CurrencyConversionService currencyConversionService,
                                       TradingUserResolver userResolver,
                                       SagaFaultInjector fault) {
        this.contractRepository = contractRepository;
        this.portfolioRepository = portfolioRepository;
        this.sagaLogRepository = sagaLogRepository;
        this.sagaLogWriter = sagaLogWriter;
        this.bankaCoreClient = bankaCoreClient;
        this.currencyConversionService = currencyConversionService;
        this.userResolver = userResolver;
        this.fault = fault;
    }

    /**
     * Pokrece SAGA iskoriscavanja OTC ugovora. Pre-saga validacija baca 4xx BEZ kreiranja loga.
     * Posle kreiranja loga svaka greska vodi u kompenzaciju. Vraca handle (sagaId/status/currentStep).
     */
    @Transactional
    public SagaResult exercise(Long contractId, Long buyerAccountId) {
        UserContext me = userResolver.resolveCurrent();
        // ── Pre-saga validacija (4xx, NEMA loga, NEMA bocnih efekata) ──────────
        // P2-7: OTC access gate PRE bilo kog citanja/loga — aktivni exercise put ide
        // iskljucivo kroz ovaj orkestrator, pa mora da forsira rolu/permisiju proveru (403)
        // da klijent koji je izgubio TRADE_STOCKS (ili nepredvidjena rola) ne moze exercise.
        ensureOtcAccess(me);
        // P2-6: pesimisticki WRITE lock na pre-saga citanju ugovora — serijalizuje
        // ACTIVE→exercise tranziciju. Dva konkurentna exercise nad istim ACTIVE ugovorom
        // se serijalizuju: drugi blokira dok prvi ne commit-uje, pa vidi status != ACTIVE
        // (vec EXERCISED) → 409. Bez lock-a oba bi prosla ACTIVE check i oba commit-ovala
        // istu rezervaciju u F3 (dvostruka naplata / lazni C3 refund / stvaranje novca).
        // Lock se uzima PRVI (pre F2/F4 portfolio lock-ova) i drzi do kraja @Transactional
        // exercise-a — konzistentan lock order, bez deadlock-a.
        //
        // P2-money-tx-1 (R3 1578) LOCK-BUDGET (svesna odluka, NE skidamo lock): ugovor
        // + portfolio row-lock-ovi se DRZE dok SAGA faze rade sinhroni REST (bankaCoreClient
        // funds legovi + currencyConversionService). Lock se NE sme skinuti i re-uzeti oko
        // poziva — ACTIVE→EXERCISED serijalizacija mora trajati ceo exercise (inace dva
        // konkurentna exercise oba prodju ACTIVE check i dvostruko commit-uju rezervaciju).
        // Lock-hold je OGRANICEN: BankaCoreClient ima connect=5s/read=30s timeout
        // (BankaCoreClientConfig) → konacan lock-budget, bez neograničene contention lavine.
        OtcContract contract = contractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new EntityNotFoundException("OTC ugovor ne postoji: " + contractId)); // 404
        if (!contract.getBuyerId().equals(me.userId()) || !contract.getBuyerRole().equals(me.userRole())) {
            throw new AccessDeniedException("Samo kupac moze iskoristiti ugovor.");                       // 403
        }
        if (contract.getStatus() != OtcContractStatus.ACTIVE) {
            throw new IllegalStateException("Ugovor nije aktivan (status=" + contract.getStatus() + ").");  // 409
        }
        if (contract.getSettlementDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("Settlement datum je prosao — ugovor je istekao.");             // 409
        }

        SagaLog saga = new SagaLog();
        saga.setSagaId(UUID.randomUUID().toString());
        saga.setContractId(contractId);
        saga.setStatus(SagaStatus.RUNNING);
        saga.setCurrentStep(0);
        // P1-1: write-ahead RUNNING red u ZASEBNOJ (REQUIRES_NEW) transakciji — komituje se odmah,
        // pa prezivi outer-tx rollback / pad koordinatora (recovery ga nadje po findByStatusIn).
        sagaLogWriter.persist(saga);

        SagaContext ctx = new SagaContext(contract, buyerAccountId);
        boolean forwardCompleted = false;
        try {
            runForward(saga, ctx);
            saga.setStatus(SagaStatus.COMPLETED);
            forwardCompleted = true;
        } catch (RuntimeException ex) {
            log.warn("SAGA {} pala u fazi F{} — kompenzujem: {}", saga.getSagaId(), saga.getCurrentStep(), ex.toString());
            saga.setStatus(SagaStatus.COMPENSATING);
            sagaLogWriter.persist(saga);
            compensate(saga, ctx, saga.getCurrentStep());
            // compensate() je vec durabilno upisao finalni status (COMPENSATED ili — ako je
            // kompenzator trajno pao — COMPENSATING/return); ovde ne preinacavamo terminal.
        }
        sagaLogWriter.persist(saga);
        if (forwardCompleted) {
            // N1 (P0-T2): OUTER-TX-COMMITTED markeri F4/F5. Postavljaju se na MANAGED kopiju ucitanu u
            // outer (exercise) persistence context PA SE flush-uju tek na outer commit-u — pa dele sudbinu
            // sa F4/F5 lokalnim JPA efektom (portfolio prenos + status flip). Real-crash pre outer commit-a
            // ponisti i efekat i marker → recovery C4/C5 ne restore-uju (nema phantom-a; nezavisno od
            // qty-snapshot heuristike koja je pucala kad prodavac proda hartije izmedju crash-a i recovery-ja
            // i za legacy null-snapshot sage). Markeri se pisu POSLE write-ahead persist-a gore (saga.version
            // je tad sinhronizovan sa redom), a managed kopija se NE dira nijednim daljim REQUIRES_NEW write-om
            // → bez @Version kolizije / NonUniqueObjectException (ne radimo dodatni save/persist, samo set polja).
            sagaLogRepository.findById(saga.getId()).ifPresent(managed -> {
                managed.setF4Committed(true);
                managed.setF5Committed(true);
            });
        }
        return new SagaResult(saga.getSagaId(), saga.getStatus(), saga.getCurrentStep());
    }

    /**
     * <b>W1.9</b> crash-recovery: jedan korak ka terminalnom stanju za zaglavljenu
     * SAGA (RUNNING/COMPENSATING posle pada). In-memory {@link SagaContext} je
     * izgubljen, pa se rekonstruise iz perzistovanog ugovora + loga, a SAGA se
     * UVEK gura ka terminalnom stanju KOMPENZACIJOM (nikad roll-forward) — sem ako
     * forward log pokazuje da su sve 5 faza vec primenjene (tada je exercise
     * efektivno zavrsen -> COMPLETED). Time se zadovoljava SG-11 ("terminal
     * Completed ILI Compensated; oboje validno ako invarijante drze") biranjem
     * sigurne Compensated grane.
     *
     * <p>Po pozivu kompenzuje preostale primenjene-ali-ne-kompenzovane korake
     * obrnutim redom. Ako jedan kompenzator padne, belezi err, OSTAVLJA status
     * COMPENSATING i ODMAH se vraca — sledeci {@link SagaRecoveryService#recoverOnce()}
     * scheduler sweep retry-uje taj korak (Primer 9 / SG-16 / I8). Ne radi
     * lokalni loop-retry (taj ima {@code exercise(...)} inline put).
     */
    @Transactional
    public void recover(String sagaId) {
        SagaLog saga = sagaLogRepository.findBySagaIdForUpdate(sagaId).orElse(null);
        if (saga == null || isTerminal(saga.getStatus())) {
            return;
        }

        Set<Integer> appliedSteps = stepsWith(saga, SagaStepKind.FORWARD);
        Set<Integer> compensatedSteps = stepsWith(saga, SagaStepKind.COMPENSATE);

        // Crash posle F5 a pre status flip-a: sve faze primenjene -> exercise zavrsen.
        // N2 (P0-T2): log "ok" zapisi NISU dovoljni za COMPLETED — moraju se RE-VERIFIKOVATI protiv
        // STVARNOG stanja baze. F4/F5 "ok" su sad outer-tx (dele sudbinu sa lokalnim efektom), ali
        // da bismo bili otporni i na zaostale write-ahead artefakte: COMPLETED iskljucivo ako je
        // contract ZAISTA EXERCISED u bazi. Real-crash posle write-ahead F5 "ok" ali pre outer commit-a
        // ostavlja contract ACTIVE → log bi (laznо) tvrdio "sve primenjeno" → re-exercise (drugi F3
        // ponovo naplati). Ako log kaze all-applied ALI contract ACTIVE → NIJE zavrseno: padni u
        // kompenzacionu granu (gura ka COMPENSATED) umesto laznog COMPLETED.
        if (appliedSteps.containsAll(ALL_STEPS) && contractIsExercised(saga.getContractId())) {
            saga.setStatus(SagaStatus.COMPLETED);
            sagaLogRepository.saveAndFlush(saga);
            return;
        }

        // remaining = primenjeni \ kompenzovani, obrnuti red (od najveceg koraka)
        NavigableSet<Integer> remaining = new TreeSet<>(appliedSteps);
        // P0-1: ukljuci i DELIMICNO primenjen failed step (nema forward "ok" jer je faza
        // pala pre kraja, ali je primenila bocne efekte koje treba kompenzovati) — odredjuje
        // se po perzistovanim partial-progress flag-ovima (f3CommitDone / f4SellerApplied).
        Integer partialStep = partiallyAppliedFailedStep(saga);
        if (partialStep != null) {
            remaining.add(partialStep);
        }
        remaining.removeAll(compensatedSteps);
        if (remaining.isEmpty()) {                       // nista za kompenzovati -> terminalno
            saga.setStatus(SagaStatus.COMPENSATED);
            sagaLogRepository.saveAndFlush(saga);
            return;
        }

        SagaContext ctx = rebuildContext(saga, remaining);
        saga.setStatus(SagaStatus.COMPENSATING);
        sagaLogRepository.saveAndFlush(saga);

        for (Integer step : remaining.descendingSet()) {
            SagaPhase phase = SagaPhase.ofStep(step);
            try {
                applyCompensator(phase, saga, ctx);
                saga.append(SagaLogEntry.ok(step, SagaStepKind.COMPENSATE));
                sagaLogRepository.saveAndFlush(saga);
            } catch (RuntimeException ex) {
                // Bez lokalnog loop-retry-ja: belezi err, ostaje COMPENSATING, vrati se.
                // Sledeci scheduler sweep retry-uje OVAJ korak (Primer 9 / SG-16 / I8).
                saga.append(SagaLogEntry.err(step, SagaStepKind.COMPENSATE, ex.toString()));
                sagaLogRepository.saveAndFlush(saga);
                log.warn("SAGA {} recovery — C{} pala, ostaje COMPENSATING (retry sledeci sweep): {}",
                        saga.getSagaId(), step, ex.toString());
                return;
            }
        }

        saga.setStatus(SagaStatus.COMPENSATED);
        sagaLogRepository.saveAndFlush(saga);
    }

    private static boolean isTerminal(SagaStatus status) {
        return status == SagaStatus.COMPLETED || status == SagaStatus.COMPENSATED
                || status == SagaStatus.FAILED;
    }

    /**
     * <b>N2 (P0-T2):</b> da li je ugovor STVARNO EXERCISED u bazi (svezo citanje). Recovery
     * COMPLETED granu sme da uzme samo ako je F5 lokalni efekat (status flip) zaista commit-ovan
     * — log "ok" zapisi mogu biti zaostali write-ahead artefakti dok je outer tx rollback-ovan.
     */
    private boolean contractIsExercised(Long contractId) {
        return contractRepository.findById(contractId)
                .map(c -> c.getStatus() == OtcContractStatus.EXERCISED)
                .orElse(false);
    }

    /**
     * <b>P0-1:</b> faza koja je pala POSLE primene nekih bocnih efekata (delimican
     * forward), pa nema forward "ok" zapis ali JESTE ostavila trag koji treba
     * kompenzovati. Detektuje se po perzistovanim partial-progress flag-ovima:
     * F3 sa {@code f3CommitDone=true} (kupac debitovan) ili F4 sa
     * {@code f4SellerApplied=true} (prodavac umanjen). Vraca {@code null} ako nema
     * delimicno primenjenog koraka (cist "pre-effects" pad — nista za kompenzovati).
     */
    private static Integer partiallyAppliedFailedStep(SagaLog saga) {
        if (Boolean.TRUE.equals(saga.getF4SellerApplied())) {
            return SagaPhase.F4.step();
        }
        if (Boolean.TRUE.equals(saga.getF3CommitDone())) {
            return SagaPhase.F3.step();
        }
        return null;
    }

    /** Skup brojeva faza koje imaju zapis date vrste sa ishodom "ok". */
    private static Set<Integer> stepsWith(SagaLog saga, SagaStepKind kind) {
        Set<Integer> steps = new HashSet<>();
        for (SagaLogEntry e : saga.getEntries()) {
            if (e.kind() == kind && "ok".equals(e.outcome())) {
                steps.add(e.phase());
            }
        }
        return steps;
    }

    /**
     * Rekonstruise {@link SagaContext} iz perzistovanog ugovora + loga (in-memory
     * ctx je izgubljen posle pada). {@code creditedToSeller} se preracunava SAMO
     * ako je F3 medju koracima koji ce se kompenzovati (C3 ga treba) — koristi
     * identican FX lanac kao F3 ({@link #computeCreditedToSeller}).
     */
    private SagaContext rebuildContext(SagaLog saga, Set<Integer> stepsToCompensate) {
        OtcContract contract = contractRepository.findById(saga.getContractId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "OTC ugovor ne postoji za recovery: " + saga.getContractId()));
        // N3 (P0-T2): efektivni buyer racun se uzima iz write-ahead SagaLog.buyerAccountId (postavljen
        // u F1, vezan za out-of-process rezervaciju), pa tek onda iz contract.buyerReservedAccountId.
        // contract.buyerReservedAccountId se u reserve-at-exercise grani commit-uje TEK u outer tx-u,
        // pa real-crash pre commit-a ostavlja contract polje null → bez ovog fallback-a recovery bi
        // C3 refundirao na DEFAULT racun (multi-account kupac). SagaLog datum prezivi (write-ahead).
        Long effectiveBuyerAccountId = saga.getBuyerAccountId() != null
                ? saga.getBuyerAccountId() : contract.getBuyerReservedAccountId();
        SagaContext ctx = new SagaContext(contract, effectiveBuyerAccountId);
        ctx.recovery = true;   // N1: real-crash kontekst → C4/C5 koriste outer-tx markere, ne in-memory/qty
        ctx.listingCurrency = resolveListingCurrency(contract.getListing());
        ctx.reservationId = contract.getBankaCoreReservationId();
        if (ctx.reservationId == null) {
            ctx.reservationId = saga.getBankaCoreReservationId();
        }
        ctx.reservedBuyerAmount = contract.getBuyerReservedAmount();
        ctx.buyerAccount = effectiveBuyerAccountId != null
                ? getAccountOrThrow(effectiveBuyerAccountId)
                : resolveBuyerAccount(contract.getBuyerId(), contract.getBuyerRole(),
                        null, ctx.listingCurrency);
        ctx.sellerAccount = resolveSellerAccount(contract.getSellerId(), contract.getSellerRole(),
                ctx.listingCurrency);
        // BUG-W2-01: recovery kompenzacija mora postovati create/reuse flag-ove tacno kao live put.
        ctx.buyerReservationCreatedHere = Boolean.TRUE.equals(saga.getBuyerReservationCreatedHere());
        ctx.sellerSharesReservedHere = Boolean.TRUE.equals(saga.getSellerSharesReservedHere());
        ctx.sellerSharesReservedAmount = saga.getSellerSharesReservedAmount() == null
                ? 0 : saga.getSellerSharesReservedAmount();
        // P0-1: recovery C3/C4 moraju biti partial-aware kao live put — citaj granularni F3/F4 progres.
        ctx.f3CommitDone = Boolean.TRUE.equals(saga.getF3CommitDone());
        ctx.f3CreditIntent = Boolean.TRUE.equals(saga.getF3CreditIntent());
        ctx.f3CreditDone = Boolean.TRUE.equals(saga.getF3CreditDone());
        // N4 (P0-T2): NE pogadjaj da li je credit izvrsen po (intent && !done) prozoru — to pokriva i
        // slucaj gde credit NIKAD nije izvrsen (crash izmedju persist(intent) i creditFunds poziva), pa
        // bi pun reverzni transfer debitovao prodavca za novac koji nikad nije primio (I1 puca) ili,
        // ako prodavac nema saldo, bacio (saga STUCK COMPENSATING zauvek). Umesto toga AUTORITATIVNO pitaj
        // banka-core da li je F3-credit idempotency kljuc VEC KONZUMIRAN (deterministicki iz sagaId).
        // consumed=TRUE → credit JESTE izvrsen → pun reverzni transfer (tacno ponisteno, I1).
        // consumed=FALSE → credit NIJE izvrsen → C3 commit-only refund kupcu (prodavac NETAKNUT).
        // Ako commit nije ni obavljen (f3CommitDone=false) preskoci upit (C3 je svakako no-op).
        if (ctx.f3CommitDone && ctx.f3CreditIntent && !ctx.f3CreditDone) {
            ctx.f3CreditMaybeApplied =
                    bankaCoreClient.isIdempotencyKeyConsumed(f3CreditIdempotencyKey(saga.getSagaId()));
        } else {
            ctx.f3CreditMaybeApplied = false;
        }
        ctx.f4SellerApplied = Boolean.TRUE.equals(saga.getF4SellerApplied());
        if (stepsToCompensate.contains(SagaPhase.F3.step())) {
            ctx.creditedToSeller = computeCreditedToSeller(ctx);
        }
        return ctx;
    }

    private void runForward(SagaLog saga, SagaContext ctx) {
        for (SagaPhase phase : SagaPhase.values()) {
            saga.setCurrentStep(phase.step());
            fault.maybeDelay(phase);
            fault.maybeFailForward(phase, "before");
            applyForward(phase, saga, ctx);          // side effects (W1.4–W1.8)
            fault.maybeFailForward(phase, "after");
            saga.append(SagaLogEntry.ok(phase.step(), SagaStepKind.FORWARD));
            // P1-1: per-faza progres je write-ahead (REQUIRES_NEW) — komituje se nezavisno od outer tx-a.
            sagaLogWriter.persist(saga);
        }
    }

    /**
     * Kompenzuje faze [failedStep .. 1] obrnutim redom; idempotentno, retry do uspeha.
     *
     * <p><b>P0-1:</b> petlja UKLJUCUJE {@code failedStep} (ne {@code failedStep - 1}).
     * Forward faza koja je primenila DELIMICNE bocne efekte pre pada (F3 commit bez
     * credit-a, F4 seller dekrement bez buyer credit-a) MORA da pokrene svoj
     * kompenzator — inace novac/akcije nestaju (Bug A / Bug B). Svaki kompenzator je
     * partial-aware (C{n} je no-op kad njegova faza nije primenila nista, i obrce tacno
     * onoliko koliko je faza stigla da primeni): C1 (createdHere + reservationId null
     * guard), C2 (reservedHere guard), C3 (f3CommitDone/f3CreditDone grananje),
     * C4 (f4SellerApplied guard + buyer snapshot), C5 (idempotentni status restore).
     */
    private void compensate(SagaLog saga, SagaContext ctx, int failedStep) {
        // zabelezi gresku poslednje pokusane faze
        saga.append(SagaLogEntry.err(failedStep, SagaStepKind.FORWARD, "forward failed"));
        sagaLogWriter.persist(saga);
        for (int step = failedStep; step >= 1; step--) {
            SagaPhase phase = SagaPhase.ofStep(step);
            int attempts = 0;
            while (true) {
                attempts++;
                try {
                    fault.maybeFailCompensator(saga.getSagaId(), phase);
                    applyCompensator(phase, saga, ctx);   // W1.4–W1.8
                    saga.append(SagaLogEntry.ok(step, SagaStepKind.COMPENSATE));
                    sagaLogWriter.persist(saga);
                    break;
                } catch (RuntimeException ce) {
                    saga.append(SagaLogEntry.err(step, SagaStepKind.COMPENSATE, ce.toString()));
                    sagaLogWriter.persist(saga);
                    if (attempts >= MAX_INLINE_COMPENSATE_ATTEMPTS) {
                        // P1-1 (defect 2): kompenzator je trajno pao (posle retry-ja). NE bacaj —
                        // propagirani izuzetak bi roll-back-ovao OUTER exercise() tx i poništio
                        // SVE saga_logs zapise (saga izgubljena). Umesto toga ostavi status
                        // COMPENSATING (durabilno) i VRATI se — sledeci SagaRecoveryService sweep
                        // retry-uje ovaj korak (identicna semantika kao recover()). COMPENSATING je
                        // ispravan izbor nad terminalnim FAILED jer je kompenzator mozda samo
                        // privremeno nedostupan (banka-core blip) → recovery ga moze dovrsiti.
                        saga.setStatus(SagaStatus.COMPENSATING);
                        sagaLogWriter.persist(saga);
                        log.warn("SAGA {} — C{} trajno pala posle {} pokusaja; ostaje COMPENSATING "
                                + "(scheduler ce retry-ovati): {}",
                                saga.getSagaId(), step, attempts, ce.toString());
                        return;
                    }
                    // retry-do-uspeha (idempotentni kompenzatori)
                }
            }
        }
        // svi kompenzatori uspeli → terminalno COMPENSATED (durabilno, REQUIRES_NEW).
        saga.setStatus(SagaStatus.COMPENSATED);
        sagaLogWriter.persist(saga);
    }

    // dispatch — bodies in W1.4–W1.8
    private void applyForward(SagaPhase phase, SagaLog saga, SagaContext ctx) {
        switch (phase) {
            case F1 -> f1ReserveBuyerFunds(saga, ctx);
            case F2 -> f2ReserveSellerShares(saga, ctx);
            case F3 -> f3TransferFunds(saga, ctx);
            case F4 -> f4TransferOwnership(saga, ctx);
            case F5 -> f5FinalizeContract(saga, ctx);
        }
    }

    private void applyCompensator(SagaPhase phase, SagaLog saga, SagaContext ctx) {
        switch (phase) {
            case F1 -> c1ReleaseBuyerFunds(saga, ctx);
            case F2 -> c2ReleaseSellerShares(saga, ctx);
            case F3 -> c3RefundBuyer(saga, ctx);
            case F4 -> c4ReturnShares(saga, ctx);
            case F5 -> c5RestoreContract(saga, ctx);
        }
    }

    // ─── F1 — rezervacija sredstava kupca (ensure-reserved, idempotentno) ───

    /**
     * <b>W1.4</b> F1: razresi racune + valutu listinga, pa <i>ensure-reserved</i>.
     * Ako ugovor vec ima {@code bankaCoreReservationId} sa pozitivnim
     * {@code buyerReservedAmount} (rezervacija nastala pri accept-u), REUSE-uj je
     * (bez {@code reserveFunds} poziva). Inace rezervisi sad (strike×qty u valuti
     * kupca); na banka-core 409 baci {@link InsufficientFundsException} (SG-03).
     */
    void f1ReserveBuyerFunds(SagaLog saga, SagaContext c) {
        OtcContract contract = c.contract;
        c.listingCurrency = resolveListingCurrency(contract.getListing());
        c.buyerAccount = resolveBuyerAccount(contract.getBuyerId(), contract.getBuyerRole(),
                c.requestedBuyerAccountId, c.listingCurrency);
        c.sellerAccount = resolveSellerAccount(contract.getSellerId(), contract.getSellerRole(),
                c.listingCurrency);
        // N3 (P0-T2): zabelezi EFEKTIVNI buyer racun (onaj sa kog F1 rezervise/REUSE-uje) na SagaLog.
        // contract.buyerReservedAccountId se u reserve-at-exercise grani postavlja tek u OUTER tx-u
        // (linija nize) pa real-crash pre commit-a izgubi taj podatak → recovery bi C3 refundirao na
        // DEFAULT racun (multi-account kupac). Ovaj write-ahead datum je vezan za out-of-process
        // rezervaciju, pa ide kroz isti write-ahead persist kao i ostali F1 progres (runForward F1).
        saga.setBuyerAccountId(c.buyerAccount.id());

        if (contract.getBankaCoreReservationId() != null
                && contract.getBuyerReservedAmount() != null
                && contract.getBuyerReservedAmount().signum() > 0) {
            c.reservationId = contract.getBankaCoreReservationId();         // accept-time reservation
            c.reservedBuyerAmount = contract.getBuyerReservedAmount();
            saga.setBankaCoreReservationId(c.reservationId);
            // BUG-W2-01: REUSE accept-time hold-a → C1 NE sme da ga oslobodi pri rollback-u.
            c.buyerReservationCreatedHere = false;
            saga.setBuyerReservationCreatedHere(false);
            return;
        }
        // reserve-at-exercise (SAGA_test seed path): strike*qty u valuti listinga -> valutu kupca
        BigDecimal costListing = contract.getStrikePrice()
                .multiply(BigDecimal.valueOf(contract.getQuantity()))
                .setScale(4, RoundingMode.HALF_UP);
        String buyerCcy = c.buyerAccount.currencyCode();
        BigDecimal reservedInBuyerCcy = buyerCcy.equals(c.listingCurrency)
                ? costListing : currencyConversionService.convert(costListing, c.listingCurrency, buyerCcy);
        try {
            ReserveFundsResponse resp = bankaCoreClient.reserveFunds(
                    "otc-saga-" + saga.getSagaId() + "-f1-reserve",
                    new ReserveFundsRequest(c.buyerAccount.id(), reservedInBuyerCcy, buyerCcy));
            c.reservationId = resp.reservationId();
            c.reservedBuyerAmount = reservedInBuyerCcy;
            saga.setBankaCoreReservationId(c.reservationId);
            contract.setBankaCoreReservationId(c.reservationId);
            contract.setBuyerReservedAmount(reservedInBuyerCcy);
            contract.setBuyerReservedAccountId(c.buyerAccount.id());
            // BUG-W2-01: OVA saga je kreirala rezervaciju → C1 je oslobadja pri rollback-u.
            c.buyerReservationCreatedHere = true;
            saga.setBuyerReservationCreatedHere(true);
        } catch (BankaCoreClientException e) {
            if (e.getHttpStatus() == 409) {
                throw new InsufficientFundsException(
                        "Kupac nema dovoljno sredstava za iskoriscavanje ugovora."); // SG-03 (F1 fail) → 400
            }
            throw e;
        }
    }

    /**
     * <b>W1.4</b> C1: oslobodi buyer-ovu rezervaciju. Idempotentno.
     *
     * <p><b>BUG-W2-01:</b> oslobadja SAMO ako je F1 OVE saga-e zaista kreirao
     * rezervaciju (reserve-at-exercise). Ako je F1 REUSE-ovao accept-time hold,
     * rollback ostavlja ugovor ACTIVE pa rezervacija MORA da ostane (Celina 4:
     * "javna kolicina zakljucana dok ugovor ne istekne ili ne bude iskoriscen").
     */
    void c1ReleaseBuyerFunds(SagaLog saga, SagaContext c) {
        if (!c.buyerReservationCreatedHere) {
            return;                                                            // accept-time hold → cuva se
        }
        if (c.reservationId == null) {
            return;                                                            // idempotent
        }
        bankaCoreClient.releaseFunds(c.reservationId, "otc-saga-" + saga.getSagaId() + "-c1-release",
                new ReleaseFundsRequest("SAGA C1 release"));
    }

    // ─── F2 — rezervacija akcija prodavca + C2 oslobadjanje ───

    /**
     * <b>W1.5</b> F2: rezervisi {@code qty} akcija prodavca (povecaj
     * {@code reservedQuantity}). Ako je rezervacija vec pokrivena (accept-time) —
     * no-op (idempotentno). Ako nema dovoljno raspolozivih akcija — SG-04 fail.
     */
    void f2ReserveSellerShares(SagaLog saga, SagaContext c) {
        OtcContract k = c.contract;
        Portfolio sp = portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                        k.getSellerId(), k.getSellerRole(), k.getListing().getId())
                .orElseThrow(() -> new IllegalStateException("Prodavac nema poziciju za ovu hartiju."));
        int reserved = sp.getReservedQuantity() == null ? 0 : sp.getReservedQuantity();
        int alreadyCoversContract = Math.min(reserved, k.getQuantity());
        if (alreadyCoversContract >= k.getQuantity()) {
            // BUG-W2-01: rezervisano pri accept-u → no-op; C2 NE sme da dekrementuje tu rezervaciju.
            c.sellerSharesReservedHere = false;
            c.sellerSharesReservedAmount = 0;
            saga.setSellerSharesReservedHere(false);
            saga.setSellerSharesReservedAmount(0);
            return;
        }
        int need = k.getQuantity() - alreadyCoversContract;
        if (sp.getAvailableQuantity() < need) {                                // SG-04 (F2 fail)
            throw new IllegalStateException("Prodavac nema dovoljno hartija za iskoriscavanje ugovora.");
        }
        sp.setReservedQuantity(reserved + need);
        portfolioRepository.save(sp);
        // BUG-W2-01: OVA saga je rezervisala `need` akcija → C2 oslobadja tacno toliko.
        c.sellerSharesReservedHere = true;
        c.sellerSharesReservedAmount = need;
        saga.setSellerSharesReservedHere(true);
        saga.setSellerSharesReservedAmount(need);
    }

    /**
     * <b>W1.5</b> C2: dekrementuj seller {@code reservedQuantity} za tacno onoliko
     * koliko je F2 OVE saga-e rezervisalo ({@code sellerSharesReservedAmount}),
     * pod floorom 0.
     *
     * <p><b>BUG-W2-01:</b> ako je F2 bio no-op (accept-time pokrice), C2 NE dira
     * {@code reservedQuantity} — accept-time rezervacija ostaje na ACTIVE ugovoru
     * (Celina 4 lock invarijanta).
     */
    void c2ReleaseSellerShares(SagaLog saga, SagaContext c) {
        if (!c.sellerSharesReservedHere) {
            return;                                                            // accept-time pokrice → cuva se
        }
        OtcContract k = c.contract;
        Portfolio sp = portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                k.getSellerId(), k.getSellerRole(), k.getListing().getId()).orElse(null);
        if (sp == null) {
            return;
        }
        int reserved = sp.getReservedQuantity() == null ? 0 : sp.getReservedQuantity();
        sp.setReservedQuantity(Math.max(0, reserved - c.sellerSharesReservedAmount));
        portfolioRepository.save(sp);
    }

    // ─── F3 — prenos sredstava + C3 povrat kupcu (fix money-loss bug) ───

    /**
     * <b>W1.6 / P0-1 (Bug A)</b> F3: naplati buyer-ovu rezervaciju ({@code commitFunds})
     * pa kreditiraj prodavca FX-konvertovani iznos ({@code creditFunds}).
     *
     * <p>F3 nije atoman — to su DVA odvojena banka-core poziva. Zato se posle SVAKE
     * noge perzistuje granularni progres ({@code f3CommitDone} / {@code f3CreditDone})
     * uz {@code saveAndFlush}, tako da C3 (i live i crash-recovery) zna TACNO koje su
     * noge obavljene i moze da ih partial-aware ponisti (kupcu vrati commit, ili obe
     * noge reverznim transferom). Bez ovoga, credit-fail posle commit-a bi (uz raniji
     * {@code failedStep-1} loop) UNISTIO novac.
     */
    void f3TransferFunds(SagaLog saga, SagaContext c) {
        BigDecimal reserved = c.reservedBuyerAmount;
        bankaCoreClient.commitFunds(c.reservationId, "otc-saga-" + saga.getSagaId() + "-f3-commit",
                new CommitFundsRequest(reserved, BigDecimal.ZERO, null,
                        "OTC SAGA " + saga.getSagaId() + " F3 — naplata rezervacije"));
        // Commit obavljen (kupac debitovan, rezervacija zatvorena) — perzistuj PRE credit-a,
        // da crash/credit-fail izmedju noga bude vidljiv C3 grananju. P1-1: kroz REQUIRES_NEW writer
        // (write-ahead) — granularni F3 progres prezivi pad izmedju commit-a i credit-a.
        c.f3CommitDone = true;
        saga.setF3CommitDone(true);
        sagaLogWriter.persist(saga);

        BigDecimal toSeller = computeCreditedToSeller(c);
        // N4 (P0-T2): write-ahead NAMERA da credit krece — perzistuj PRE poziva. creditFunds je
        // out-of-process i durable; real-crash izmedju njega i persist(f3CreditDone) bi ostavio
        // commit=true/credit=false → naivni C3 refundira SAMO kupca a prodavceva isplata ostane →
        // stvaranje novca. Sa intent flag-om recovery zna da je credit MOZDA izvrsen pa radi pun
        // reverzni transfer (idempotentni banka-core kljucevi). creditedToSeller se belezi PRE poziva
        // da bi C3/recovery imali tacan reverzni iznos i kad credit padne posle intent-a.
        c.creditedToSeller = toSeller;
        c.f3CreditIntent = true;
        saga.setF3CreditIntent(true);
        sagaLogWriter.persist(saga);

        String sellerCcy = c.sellerAccount.currencyCode();
        bankaCoreClient.creditFunds(f3CreditIdempotencyKey(saga.getSagaId()),
                new CreditFundsRequest(c.sellerAccount.id(), toSeller, BigDecimal.ZERO, sellerCcy,
                        "OTC SAGA " + saga.getSagaId() + " F3 — isplata prodavcu"));
        c.f3CreditDone = true;
        saga.setF3CreditDone(true);
        sagaLogWriter.persist(saga);
    }

    /**
     * FX lanac za iznos koji F3 kreditira prodavcu: {@code reservedBuyerAmount}
     * (valuta kupca) -> valuta listinga -> valuta prodavca. Izdvojeno (DRY) da bi
     * {@link #recover(String)} mogao da rekonstruise {@code creditedToSeller} kad
     * je in-memory {@link SagaContext} izgubljen posle pada (mora koristiti
     * IDENTICAN lanac kao F3 da bi C3 reverzni transfer bio tacan — I1).
     */
    private BigDecimal computeCreditedToSeller(SagaContext c) {
        BigDecimal reserved = c.reservedBuyerAmount;
        String buyerCcy = c.buyerAccount.currencyCode();
        BigDecimal inListing = buyerCcy.equals(c.listingCurrency) ? reserved
                : currencyConversionService.convert(reserved, buyerCcy, c.listingCurrency);
        String sellerCcy = c.sellerAccount.currencyCode();
        return c.listingCurrency.equals(sellerCcy) ? inListing
                : currencyConversionService.convert(inListing, c.listingCurrency, sellerCcy);
    }

    /**
     * <b>W1.6 / P0-1 (Bug A)</b> C3: partial-aware povrat novca, grana po
     * granularnom F3 progresu ({@code f3CommitDone} / {@code f3CreditDone}):
     *
     * <ul>
     *   <li><b>Obe noge ({@code f3CreditDone}):</b> reverzni transfer prodavac→kupac —
     *       debit prodavca {@code creditedToSeller} + credit kupca {@code reservedBuyerAmount}.
     *       OBE noge se ponistavaju → I1 ocuvan. (krediranje samo kupca bi STVORILO novac
     *       jer bi prodavac zadrzao F3 priliv.)</li>
     *   <li><b>Samo commit ({@code f3CommitDone} a NE credit):</b> prodavac NIKAD nije
     *       kreditiran — NE sme se debitovati. Kupcu se vraca commit-ovani iznos
     *       ({@code reservedBuyerAmount}) jednostranim {@code creditFunds} → I1 ocuvan
     *       (vraca tacno onoliko koliko je commit oduzeo). Ovo je popravka money-loss buga.</li>
     *   <li><b>Nista:</b> F3 nije primenio bocne efekte → no-op (idempotentno).</li>
     * </ul>
     *
     * <p>Idempotentni banka-core kljucevi su deterministicki po sagaId-u i grani, pa
     * eventualni retry (live ili recovery) ne dvostruko-kreditira.
     */
    void c3RefundBuyer(SagaLog saga, SagaContext c) {
        // N4 (P0-T2): pun reverzni transfer kad je credit POTVRDJEN (f3CreditDone, live) ILI kad je
        // recovery AUTORITATIVNO utvrdio da je credit izvrsen (f3CreditMaybeApplied — banka-core dedup
        // store kaze da je F3-credit kljuc KONZUMIRAN). U OBA slucaja prodavac JESTE kreditiran, pa se
        // obe noge ponistavaju → I1 ocuvan. Ako credit NIJE izvrsen (recovery query vratio consumed=false,
        // crash izmedju persist(intent) i creditFunds poziva), f3CreditMaybeApplied je FALSE → padamo u
        // commit-only granu nize (prodavac NETAKNUT). Tako se ne debituje prodavac za novac koji nikad nije
        // primio (I1 / STUCK) niti se stvara novac (kad jeste primio). Pogadjanje je zamenjeno upitom.
        if (c.f3CreditDone || c.f3CreditMaybeApplied) {
            // credit potvrdjen (live) ili autoritativno utvrdjen (recovery) → reverzni transfer
            bankaCoreClient.transferFunds("otc-saga-" + saga.getSagaId() + "-c3-refund",
                    new TransferFundsRequest(
                            c.sellerAccount.id(), c.creditedToSeller,
                            c.buyerAccount.id(), c.reservedBuyerAmount,
                            BigDecimal.ZERO, null,
                            "OTC SAGA " + saga.getSagaId() + " C3 — povratni transfer (kompenzacija)"));
            return;
        }
        if (c.f3CommitDone) {
            // commit obavljen, credit NIJE → prodavac nikad kreditiran, vrati SAMO kupcu.
            String buyerCcy = c.buyerAccount.currencyCode();
            bankaCoreClient.creditFunds("otc-saga-" + saga.getSagaId() + "-c3-refund-commit",
                    new CreditFundsRequest(c.buyerAccount.id(), c.reservedBuyerAmount,
                            BigDecimal.ZERO, buyerCcy,
                            "OTC SAGA " + saga.getSagaId() + " C3 — povrat commit-a kupcu (kompenzacija)"));
        }
        // ni commit ni credit → F3 nije primenio nista → no-op
    }

    // ─── F4 — prenos vlasnistva akcija + C4 vracanje (snapshot-based) ───

    /**
     * <b>W1.7 / P0-1 (Bug B)</b> F4: prebaci {@code qty} akcija seller→buyer; snapshot-uj
     * buyer-ovo pre-F4 stanje na {@link SagaLog} radi tacnog C4 restore-a
     * (averageBuyPrice je lossy).
     *
     * <p>F4 nije atoman — prvo dekrementuje seller poziciju (save/delete), pa kreditira
     * buyer poziciju. Posle seller-leg-a (PRE buyer-leg-a) perzistuje se
     * {@code f4SellerApplied=true} uz {@code saveAndFlush}, tako da C4 (i live i recovery)
     * zna da mora vratiti seller akcije ako buyer-leg padne. Bez ovoga, buyer-leg fail
     * (npr. pessimistic-lock timeout) bi (uz raniji {@code failedStep-1} loop) UNISTIO
     * akcije (seller umanjen, buyer nista).
     */
    void f4TransferOwnership(SagaLog saga, SagaContext c) {
        OtcContract k = c.contract;
        Portfolio seller = portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                        k.getSellerId(), k.getSellerRole(), k.getListing().getId())
                .orElseThrow(() -> new IllegalStateException("Prodavac vise nema ovu hartiju."));
        if (seller.getQuantity() < k.getQuantity()) {
            throw new IllegalStateException("Prodavac nema dovoljno akcija.");
        }
        // N1 (P0-T2): snapshot prodavceve qty PRE dekrementa (dijagnosticki/audit; write-ahead u
        // runForward F4 persist). recovery C4 vise NE koristi qty-snapshot heuristiku (lossy, pucala je
        // ako prodavac proda hartije izmedju crash-a i recovery-ja) — umesto toga oslanja se na OUTER-TX
        // marker f4Committed (vidi c4ReturnShares). Snapshot ostaje radi audit/log konteksta.
        saga.setPreF4SellerQuantity(seller.getQuantity());
        seller.setQuantity(seller.getQuantity() - k.getQuantity());
        int sellerReserved = seller.getReservedQuantity() == null ? 0 : seller.getReservedQuantity();
        seller.setReservedQuantity(Math.max(0, sellerReserved - k.getQuantity()));
        int sellerPublic = seller.getPublicQuantity() == null ? 0 : seller.getPublicQuantity();
        seller.setPublicQuantity(Math.max(0, sellerPublic - k.getQuantity()));
        if (seller.getQuantity() <= 0) {
            portfolioRepository.delete(seller);
        } else {
            portfolioRepository.save(seller);
        }
        // Seller-leg primenjen i komitovan — perzistuj PRE buyer-leg-a (Bug B granularitet).
        // P1-1: kroz REQUIRES_NEW writer (write-ahead) — f4SellerApplied prezivi pad izmedju nogu
        // (in-process compensate ga koristi za partial-safe C4). N1 (P0-T2): RECOVERY (real-crash) NE
        // sme da veruje ovom write-ahead flag-u za seller-restore odluku — F4 je all-or-nothing sa outer
        // tx-om (seller dekrement I buyer credit su oba lokalni JPA u istom tx-u), pa real-crash pre
        // outer commit-a ponisti OBE noge; recovery rekonciliuje protiv STVARNOG stanja baze
        // (contract EXERCISED witness) umesto da slepo restore-uje (vidi rebuildContext / reconcileLocalF4F5).
        c.f4SellerApplied = true;
        saga.setF4SellerApplied(true);
        sagaLogWriter.persist(saga);

        // P0-1 test hook: forsiran pad U SREDINI F4 (posle seller dekrementa, pre buyer credit-a).
        fault.maybeFailForwardMid(SagaPhase.F4);

        Optional<Portfolio> existing = portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                k.getBuyerId(), k.getBuyerRole(), k.getListing().getId());
        if (existing.isPresent()) {
            Portfolio bp = existing.get();
            saga.setPreF4BuyerExisted(true);
            saga.setPreF4BuyerQuantity(bp.getQuantity());
            saga.setPreF4BuyerAvgPrice(bp.getAverageBuyPrice());
            int oldQty = bp.getQuantity();
            BigDecimal oldTotal = bp.getAverageBuyPrice().multiply(BigDecimal.valueOf(oldQty));
            BigDecimal addTotal = k.getStrikePrice().multiply(BigDecimal.valueOf(k.getQuantity()));
            int newQty = oldQty + k.getQuantity();
            bp.setQuantity(newQty);
            bp.setAverageBuyPrice(oldTotal.add(addTotal)
                    .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP));
            portfolioRepository.save(bp);
        } else {
            saga.setPreF4BuyerExisted(false);
            Portfolio bp = new Portfolio();
            bp.setUserId(k.getBuyerId());
            bp.setUserRole(k.getBuyerRole());
            bp.setListingId(k.getListing().getId());
            bp.setListingTicker(k.getListing().getTicker());
            bp.setListingName(k.getListing().getName());
            bp.setListingType(k.getListing().getListingType().name());
            bp.setQuantity(k.getQuantity());
            bp.setAverageBuyPrice(k.getStrikePrice());
            bp.setPublicQuantity(0);
            portfolioRepository.save(bp);
        }
    }

    /**
     * <b>W1.7 / P0-1 (Bug B)</b> C4: partial-safe vracanje akcija.
     *
     * <p><b>Guard ({@code f4SellerApplied}):</b> ako F4 NIJE dekrementovao prodavca
     * (pao pre seller-leg-a), C4 je no-op — NE sme da DODA akcije prodavcu (stvorio
     * bi ih). Vraca/dira stanje SAMO kad je seller-leg primenjen.
     *
     * <p><b>Buyer leg ({@code preF4BuyerExisted != null}):</b> buyer poziciju dira
     * samo ako je buyer-blok u F4 ZAISTA izvrsen (snapshot postavljen). Ako je F4 pao
     * U SREDINI (posle seller dekrementa, pre buyer credit-a) → buyer-blok nije ni
     * krenuo → C4 ne dira buyer poziciju (inace bi obrisao tudji nevezani red).
     *
     * <ul>
     *   <li>buyer postojao pre F4 → restore qty+avg iz snapshot-a;</li>
     *   <li>buyer NIJE postojao (F4 ga kreirao) → obrisi red;</li>
     *   <li>buyer-blok nije ni izvrsen (snapshot null) → ne diraj buyer.</li>
     * </ul>
     *
     * <p>Seller akcije + reservedQuantity vracene (re-kreira se obrisan seller red ako
     * je F4 izbrisao poziciju jer je pala na 0).
     *
     * <p><b>N1 (P0-T2) OUTER-TX MARKER (zamenjuje qty-snapshot heuristiku):</b> u RECOVERY-ju
     * (real-crash) F4 seller-leg i buyer-leg su OBA lokalni JPA u outer (exercise) tx-i
     * (all-or-nothing). Real-crash pre outer commit-a ponisti OBE noge zajedno sa OUTER-TX
     * markerom {@code f4Committed} (postavlja se na managed entitet u istoj outer tx-i). Zato
     * recovery C4 restore-uje SAMO ako je {@code f4Committed == true}; inace no-op. Ovo je
     * robusnije od ranije qty-snapshot heuristike ({@code currentQty >= preF4Snapshot}) koja je
     * (a) lazno restore-ovala ako prodavac PRODA hartije izmedju crash-a i recovery-ja (qty padne
     * iz drugog razloga → phantom) i (b) bezuslovno restore-ovala za legacy null-snapshot sage.
     * U LIVE in-process compensate-u marker je jos {@code false} (postavlja se tek na outer commit),
     * ali su F4 efekti u OTVORENOJ tx-i pa se restore-uju preko in-memory {@code f4SellerApplied}.
     */
    void c4ReturnShares(SagaLog saga, SagaContext c) {
        if (!Boolean.TRUE.equals(saga.getF4SellerApplied()) && !c.f4SellerApplied) {
            return;   // F4 nije dekrementovao prodavca → nista za vratiti (no-op).
        }
        // N1 (P0-T2): RECOVERY grana se oslanja na OUTER-TX marker, ne na in-memory/qty heuristiku.
        // f4Committed != true → F4 lokalni efekti (seller dekrement + buyer credit) su rollback-ovani sa
        // outer tx-om (ili je saga legacy bez markera) → nista durable za kompenzovati → no-op (nema
        // phantom-a, I2 ocuvan; bez obzira da li je prodavac u medjuvremenu menjao svoju poziciju).
        if (c.recovery && !Boolean.TRUE.equals(saga.getF4Committed())) {
            return;
        }
        OtcContract k = c.contract;

        Optional<Portfolio> spOptPre = portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                k.getSellerId(), k.getSellerRole(), k.getListing().getId());

        // Buyer leg: diraj SAMO ako je buyer-blok u F4 zaista izvrsen (snapshot postavljen).
        if (saga.getPreF4BuyerExisted() != null) {
            Optional<Portfolio> bpOpt = portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    k.getBuyerId(), k.getBuyerRole(), k.getListing().getId());
            if (Boolean.TRUE.equals(saga.getPreF4BuyerExisted())) {
                bpOpt.ifPresent(bp -> {
                    bp.setQuantity(saga.getPreF4BuyerQuantity());
                    bp.setAverageBuyPrice(saga.getPreF4BuyerAvgPrice());
                    portfolioRepository.save(bp);
                });
            } else {
                bpOpt.ifPresent(portfolioRepository::delete);   // kreirali smo ga u F4
            }
        }

        // Seller leg: vrati akcije + reservedQuantity (re-kreiraj ako je F4 obrisao red).
        Optional<Portfolio> spOpt = spOptPre;
        if (spOpt.isPresent()) {
            Portfolio sp = spOpt.get();
            sp.setQuantity(sp.getQuantity() + k.getQuantity());
            int reserved = sp.getReservedQuantity() == null ? 0 : sp.getReservedQuantity();
            sp.setReservedQuantity(reserved + k.getQuantity());
            portfolioRepository.save(sp);
        } else {
            Portfolio sp = new Portfolio();
            sp.setUserId(k.getSellerId());
            sp.setUserRole(k.getSellerRole());
            sp.setListingId(k.getListing().getId());
            sp.setListingTicker(k.getListing().getTicker());
            sp.setListingName(k.getListing().getName());
            sp.setListingType(k.getListing().getListingType().name());
            sp.setQuantity(k.getQuantity());
            sp.setReservedQuantity(k.getQuantity());
            sp.setAverageBuyPrice(k.getStrikePrice());
            sp.setPublicQuantity(0);
            portfolioRepository.save(sp);
        }
    }

    // ─── F5 — finalizacija ugovora + C5 vracanje ───

    /** <b>W1.8</b> F5: status ACTIVE→EXERCISED + exercisedAt. */
    void f5FinalizeContract(SagaLog saga, SagaContext c) {
        c.contract.setStatus(OtcContractStatus.EXERCISED);
        c.contract.setExercisedAt(LocalDateTime.now());
        contractRepository.save(c.contract);
    }

    /**
     * <b>W1.8</b> C5: status EXERCISED→ACTIVE + exercisedAt=null.
     *
     * <p><b>N1 (P0-T2) OUTER-TX MARKER:</b> u RECOVERY-ju ako F5 status-flip NIJE outer-tx-committed
     * ({@code f5Committed != true}, ukljucujuci legacy null), real-crash ga je vec rollback-ovao
     * (contract je opet ACTIVE) → C5 je no-op (ne pisemo nepotrebno). Ako jeste committed → saga bi
     * bila COMPLETED i C5 se ne bi ni dosegnuo; svejedno se status restore-uje radi simetrije. U LIVE
     * compensate-u marker je jos false ali je flip u otvorenoj tx-i → restore se izvrsava (ctx.recovery=false).
     */
    void c5RestoreContract(SagaLog saga, SagaContext c) {
        if (c.recovery && !Boolean.TRUE.equals(saga.getF5Committed())) {
            return;   // F5 flip rollback-ovan sa outer tx-om (ili legacy) → contract vec ACTIVE → no-op
        }
        c.contract.setStatus(OtcContractStatus.ACTIVE);
        c.contract.setExercisedAt(null);
        contractRepository.save(c.contract);
    }

    // ─── resolve-helpers (kopirani iz OtcService, prilagodjeni orchestratoru) ───

    /**
     * <b>P2-7</b> OTC access gate. R1 783 / R2 1443: logika je izdvojena u
     * deljeni static {@link OtcAccessPolicy#ensureOtcAccess(UserContext)} (isti
     * izvor istine koji koristi i {@code OtcService}). Static utility nema bean
     * zavisnost, pa nema ciklusa orkestrator → {@code OtcService}; ranija verbatim
     * replika je uklonjena.
     *
     * <p>Spec Celina 4 (Nova) §145-148: OTC dozvoljen samo SUPERVIZORIMA (od zaposlenih)
     * i KLIJENTIMA sa {@code TRADE_STOCKS} permisijom (P1-6); agenti su iskljuceni.
     * Defense-in-depth (npr. za klijenta koji je izgubio trade pravo posle izdavanja
     * JWT-a). Baca {@code AccessDeniedException} (→ 403) kad pristup nije dozvoljen.
     */
    private void ensureOtcAccess(UserContext user) {
        OtcAccessPolicy.ensureOtcAccess(user);
    }

    private String resolveListingCurrency(Listing listing) {
        return ListingCurrencyResolver.resolve(listing);
    }

    /**
     * Razresava buyer-ov racun. Ako je {@code requestedAccountId} prosledjen,
     * koristi se (uz proveru vlasnistva); inace podrazumevani racun u valuti listinga.
     */
    private InternalAccountDto resolveBuyerAccount(Long buyerId, String buyerRole,
                                                   Long requestedAccountId, String listingCurrency) {
        if (requestedAccountId != null) {
            InternalAccountDto account = getAccountOrThrow(requestedAccountId);
            verifyAccountOwnership(account, buyerId, buyerRole);
            return account;
        }
        return findDefaultAccount(buyerId, buyerRole, listingCurrency);
    }

    private InternalAccountDto resolveSellerAccount(Long sellerId, String sellerRole, String listingCurrency) {
        return findDefaultAccount(sellerId, sellerRole, listingCurrency);
    }

    private InternalAccountDto findDefaultAccount(Long userId, String role, String preferredCurrency) {
        try {
            return bankaCoreClient.getPreferredAccount(role, userId, preferredCurrency);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException(
                        UserRole.isClient(role)
                                ? "Korisnik #" + userId + " nema aktivan racun."
                                : "Bankin racun u " + preferredCurrency + " ne postoji.");
            }
            throw ex;
        }
    }

    private InternalAccountDto getAccountOrThrow(Long accountId) {
        try {
            return bankaCoreClient.getAccount(accountId);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException("Racun ne postoji: " + accountId);
            }
            throw ex;
        }
    }

    private void verifyAccountOwnership(InternalAccountDto account, Long userId, String role) {
        if (UserRole.isClient(role)) {
            if (account.ownerClientId() == null || !userId.equals(account.ownerClientId())) {
                throw new AccessDeniedException("Racun " + account.accountNumber()
                        + " ne pripada korisniku.");
            }
        }
        // Za EMPLOYEE — pretpostavka je da je racun bankin; ne proveravamo vlasnistvo striktno.
    }
}
