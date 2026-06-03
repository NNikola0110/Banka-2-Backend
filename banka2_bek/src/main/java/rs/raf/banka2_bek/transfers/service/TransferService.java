package rs.raf.banka2_bek.transfers.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.notification.model.NotificationType;
import rs.raf.banka2_bek.notification.service.NotificationService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.transfers.model.Transfer;
import rs.raf.banka2_bek.transfers.model.TransferType;
import rs.raf.banka2_bek.transfers.dto.TransferFxRequestDto;
import rs.raf.banka2_bek.transfers.dto.TransferInternalRequestDto;
import rs.raf.banka2_bek.transfers.dto.TransferResponseDto;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.transfers.repository.TransferRepository;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.service.AuditLogService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final ExchangeService exchangeService;
    private final ClientRepository clientRepository;

    private final NotificationService notificationService;
    // BE-PAY-01: audit hooks za transfer flow
    private final AuditLogService auditLogService;

    @Value("${bank.registration-number}")
    private String bankRegistrationNumber;

    public TransferService(TransferRepository transferRepository,
                           AccountRepository accountRepository,
                           ExchangeService exchangeService,
                           ClientRepository clientRepository,
                           NotificationService notificationService,
                           AuditLogService auditLogService) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.exchangeService = exchangeService;
        this.clientRepository = clientRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    /**
     * BE-PAY-01 audit hook helper: best-effort.
     */
    private void recordAuditSafe(Long actorId, String actorType, AuditActionType action,
                                 String description, String targetType, Long targetId) {
        try {
            auditLogService.record(actorId, actorType, action, description, targetType, targetId);
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action={} target={}/{}: {}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    private TransferResponseDto mapToDto(Transfer transfer) {
        TransferResponseDto response = new TransferResponseDto();
        response.setId(transfer.getId());
        response.setOrderNumber(transfer.getOrderNumber());
        response.setFromAccountNumber(transfer.getFromAccount().getAccountNumber());
        response.setToAccountNumber(transfer.getToAccount().getAccountNumber());
        response.setAmount(transfer.getFromAmount());
        response.setToAmount(transfer.getToAmount());
        response.setFromCurrency(transfer.getFromCurrency().getCode());
        response.setToCurrency(transfer.getToCurrency().getCode());
        response.setExchangeRate(transfer.getExchangeRate());
        response.setCommission(transfer.getCommission());
        response.setClientFirstName(transfer.getCreatedBy().getFirstName());
        response.setClientLastName(transfer.getCreatedBy().getLastName());
        response.setStatus(transfer.getStatus());
        response.setCreatedAt(transfer.getCreatedAt());
        return response;
    }


    /**
     * P2-concurrency-locks-1 (R3-1581): zakljucava dva racuna pesimisticki u KANONSKOM
     * redosledu (po account-number-u) i vraca ih u (from, to) orijentaciji da bi se
     * izbegao ABBA DB deadlock (transfer A→B + paralelno B→A bi lock-ovao racune u
     * suprotnim redosledima). Mirror-uje {@code InternalFundsService.transfer}
     * (sort-po-kljucu pre lock-a). Same-account zahtev se odbija pre lock-a.
     *
     * @return niz {@code [fromAccount, toAccount]} — oba pesimisticki zakljucana.
     */
    private Account[] lockTwoAccountsCanonically(String fromAccountNumber, String toAccountNumber) {
        if (fromAccountNumber.equals(toAccountNumber)) {
            throw new IllegalArgumentException("Accounts must be different");
        }
        String firstNum = fromAccountNumber.compareTo(toAccountNumber) <= 0 ? fromAccountNumber : toAccountNumber;
        String secondNum = firstNum.equals(fromAccountNumber) ? toAccountNumber : fromAccountNumber;

        Account first = accountRepository.findForUpdateByAccountNumber(firstNum)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        firstNum.equals(fromAccountNumber) ? "From account not found" : "To account not found"));
        Account second = accountRepository.findForUpdateByAccountNumber(secondNum)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        secondNum.equals(fromAccountNumber) ? "From account not found" : "To account not found"));

        Account fromAccount = first.getAccountNumber().equals(fromAccountNumber) ? first : second;
        Account toAccount = first.getAccountNumber().equals(toAccountNumber) ? first : second;
        return new Account[]{fromAccount, toAccount};
    }

    /**
     * R1-651: zajednicki preambl koji su {@link #internalTransfer} i {@link #fxTransfer}
     * imali duplirano (~18 linija svaki): pesimisticki lock dva racuna u kanonskom
     * redosledu, provera da su oba ACTIVE, provera pristupa autentifikovanog klijenta
     * i provera istog vlasnika (interni/FX transfer ide samo izmedju sopstvenih racuna).
     *
     * @return niz {@code [actor, fromAccount, toAccount]} — racuni pesimisticki zakljucani.
     */
    private Object[] lockAndAuthorizeTransfer(String fromAccountNumber, String toAccountNumber) {
        Account[] locked = lockTwoAccountsCanonically(fromAccountNumber, toAccountNumber);
        Account fromAccount = locked[0];
        Account toAccount = locked[1];

        if (fromAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Source account is not active");
        }
        if (toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Destination account is not active");
        }

        Client actor = getAuthenticatedClient();
        ensureAccess(actor, fromAccount);
        ensureAccess(actor, toAccount);
        ensureSameOwner(fromAccount, toAccount);
        return new Object[]{actor, fromAccount, toAccount};
    }

    @Transactional
    public TransferResponseDto internalTransfer(TransferInternalRequestDto request) {

        // R1-651: lock (deadlock-free kanonski redosled) + ACTIVE + access + same-owner.
        Object[] prepared = lockAndAuthorizeTransfer(
                request.getFromAccountNumber(), request.getToAccountNumber());
        Client actor = (Client) prepared[0];
        Account fromAccount = (Account) prepared[1];
        Account toAccount = (Account) prepared[2];

        // Auto-detect FX: ako su razlicite valute, preusmeri na FX transfer
        if (!fromAccount.getCurrency().getId().equals(toAccount.getCurrency().getId())) {
            TransferFxRequestDto fxRequest = new TransferFxRequestDto();
            fxRequest.setFromAccountNumber(request.getFromAccountNumber());
            fxRequest.setToAccountNumber(request.getToAccountNumber());
            fxRequest.setAmount(request.getAmount());
            return fxTransfer(fxRequest);
        }

        if (fromAccount.getAvailableBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }

        fromAccount.setBalance(fromAccount.getBalance().subtract(request.getAmount()));
        fromAccount.setAvailableBalance(fromAccount.getAvailableBalance().subtract(request.getAmount()));
        toAccount.setBalance(toAccount.getBalance().add(request.getAmount()));
        toAccount.setAvailableBalance(toAccount.getAvailableBalance().add(request.getAmount()));

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        Transfer transfer = new Transfer();
        transfer.setOrderNumber(UUID.randomUUID().toString().replace("-", "").substring(0, 30));
        transfer.setFromAccount(fromAccount);
        transfer.setToAccount(toAccount);
        transfer.setFromAmount(request.getAmount());
        transfer.setToAmount(request.getAmount());
        transfer.setFromCurrency(fromAccount.getCurrency());
        transfer.setToCurrency(toAccount.getCurrency());
        transfer.setExchangeRate(null);
        transfer.setCommission(BigDecimal.ZERO);
        transfer.setTransferType(TransferType.INTERNAL_TRANSFER);
        transfer.setStatus(PaymentStatus.COMPLETED);
        transfer.setCreatedBy(actor);

        transferRepository.save(transfer);

        try {
            notificationService.notify(
                    actor.getId(),
                    "CLIENT",
                    NotificationType.TRANSFER,
                    "Transfer izvršen",
                    "Vaš interni transfer od " + request.getAmount() + " " + fromAccount.getCurrency().getCode() + " je uspešno izvršen.",
                    "TRANSFER",
                    transfer.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send transfer notification: {}", e.getMessage());
        }

        // BE-PAY-01: audit hook za internal transfer
        recordAuditSafe(
                actor.getId(), "CLIENT",
                AuditActionType.TRANSFER_INTERNAL,
                "Internal transfer " + request.getAmount() + " " + fromAccount.getCurrency().getCode()
                        + " from " + fromAccount.getAccountNumber() + " to " + toAccount.getAccountNumber(),
                "TRANSFER", transfer.getId());

        return mapToDto(transfer);
    }

    @Transactional
    public TransferResponseDto fxTransfer(TransferFxRequestDto request) {

        // R1-651: lock (deadlock-free kanonski redosled, R3-1581) + ACTIVE + access + same-owner.
        Object[] prepared = lockAndAuthorizeTransfer(
                request.getFromAccountNumber(), request.getToAccountNumber());
        Client actor = (Client) prepared[0];
        Account fromAccount = (Account) prepared[1];
        Account toAccount = (Account) prepared[2];

        if (fromAccount.getCurrency().getId().equals(toAccount.getCurrency().getId())) {
            throw new IllegalArgumentException("Accounts must have different currencies");
        }

        // Calculate commission: 0.5% of transfer amount (R1-650: jedinstven izvor istine
        // — ExchangeService.COMMISSION_RATE_BD; pre je 0.005 bilo duplirano kao goli literal).
        BigDecimal commissionRate = ExchangeService.COMMISSION_RATE_BD;
        BigDecimal commissionAmount = request.getAmount().multiply(commissionRate).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal totalDebit = request.getAmount().add(commissionAmount);

        // Check client can pay amount + commission
        if (fromAccount.getAvailableBalance().compareTo(totalDebit) < 0) {
            throw new IllegalArgumentException("Nedovoljno sredstava. Potrebno: " + totalDebit + " " +
                    fromAccount.getCurrency().getCode() + " (iznos + provizija 0.5%)");
        }

        // Lock bank accounts for the two currencies involved
        String fromCurrencyCode = fromAccount.getCurrency().getCode();
        String toCurrencyCode = toAccount.getCurrency().getCode();

        Account bankFromAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, fromCurrencyCode)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Bank account for " + fromCurrencyCode + " not found"));
        Account bankToAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, toCurrencyCode)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Bank account for " + toCurrencyCode + " not found"));

        // P0-B4: BigDecimal FX put — conservation-exact (nema double round-greske u knjizi).
        // Calculate exchange (includes 2% markup in rate)
        ExchangeService.FxConversionResult exchangeResult = exchangeService.calculateCrossExact(
                request.getAmount(),
                fromCurrencyCode,
                toCurrencyCode
        );

        BigDecimal toAmount = exchangeResult.convertedAmount();
        BigDecimal exchangeRate = exchangeResult.exchangeRate();

        // Check bank has enough of the target currency
        if (bankToAccount.getAvailableBalance().compareTo(toAmount) < 0) {
            throw new IllegalArgumentException("Bank does not have enough " + toCurrencyCode + " reserves");
        }

        // 1. Client pays source currency + commission
        fromAccount.setBalance(fromAccount.getBalance().subtract(totalDebit));
        fromAccount.setAvailableBalance(fromAccount.getAvailableBalance().subtract(totalDebit));

        // 2. Bank receives source currency + commission (amount + commission goes to bank)
        bankFromAccount.setBalance(bankFromAccount.getBalance().add(totalDebit));
        bankFromAccount.setAvailableBalance(bankFromAccount.getAvailableBalance().add(totalDebit));

        // 3. Bank pays target currency
        bankToAccount.setBalance(bankToAccount.getBalance().subtract(toAmount));
        bankToAccount.setAvailableBalance(bankToAccount.getAvailableBalance().subtract(toAmount));

        // 4. Client receives target currency
        toAccount.setBalance(toAccount.getBalance().add(toAmount));
        toAccount.setAvailableBalance(toAccount.getAvailableBalance().add(toAmount));

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
        accountRepository.save(bankFromAccount);
        accountRepository.save(bankToAccount);

        Transfer transfer = new Transfer();
        transfer.setOrderNumber(UUID.randomUUID().toString().replace("-", "").substring(0, 30));
        transfer.setFromAccount(fromAccount);
        transfer.setToAccount(toAccount);
        transfer.setFromAmount(request.getAmount());
        transfer.setToAmount(toAmount);
        transfer.setFromCurrency(fromAccount.getCurrency());
        transfer.setToCurrency(toAccount.getCurrency());
        transfer.setExchangeRate(exchangeRate);
        transfer.setCommission(commissionAmount);
        transfer.setTransferType(TransferType.EXCHANGE);
        transfer.setStatus(PaymentStatus.COMPLETED);
        transfer.setCreatedBy(actor);

        transferRepository.save(transfer);

        try {
            notificationService.notify(
                    actor.getId(),
                    "CLIENT",
                    NotificationType.TRANSFER,
                    "FX transfer izvršen",
                    "Vaš devizni transfer od " + request.getAmount() + " " + fromAccount.getCurrency().getCode() + " je uspešno izvršen.",
                    "TRANSFER",
                    transfer.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send FX transfer notification: {}", e.getMessage());
        }

        // BE-PAY-01: audit hook za FX transfer
        recordAuditSafe(
                actor.getId(), "CLIENT",
                AuditActionType.TRANSFER_FX,
                "FX transfer " + request.getAmount() + " " + fromCurrencyCode
                        + " -> " + toAmount + " " + toCurrencyCode + " (rate=" + exchangeRate
                        + ", commission=" + commissionAmount + ")",
                "TRANSFER", transfer.getId());

        return mapToDto(transfer);
    }

    public List<TransferResponseDto> getAllTransfers(Client client, String accountNumber, java.time.LocalDate fromDate, java.time.LocalDate toDate) {
        // R1-653 (perf): jedan fetch-join upit (filteri u WHERE) umesto ucitaj-sve +
        // in-memory filter + N+1 lazy fetch. Blank accountNumber -> null (ignorise se).
        String acc = (accountNumber != null && !accountNumber.isBlank()) ? accountNumber : null;
        java.time.LocalDateTime fromDateTime = fromDate != null ? fromDate.atStartOfDay() : null;
        java.time.LocalDateTime toDateTime = toDate != null ? toDate.atTime(23, 59, 59) : null;

        return transferRepository.findForClientWithFilters(client, acc, fromDateTime, toDateTime)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    public TransferResponseDto getTransferById(Long id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Transfer not found"));
        // P0-B9 N4 (IDOR): bez owner-check-a bilo koji autentifikovani klijent je
        // mogao da procita TUDJI transfer (racuni, iznosi, valute, provizija)
        // sekvencijalnim id-em. CLIENT sme samo transfer u kojem je strana
        // (kreator ILI vlasnik from/to racuna); EMPLOYEE/ADMIN bilo koji; no-auth
        // interni poziv (unit/scheduler) prolazi nepromenjen.
        assertTransferAccessibleByCaller(transfer);
        return mapToDto(transfer);
    }

    /**
     * P0-B9 N4 ownership guard: baca {@link org.springframework.security.access.AccessDeniedException}
     * (-&gt; HTTP 403) ako je pozivalac CLIENT a nije strana u transferu. Zaposleni
     * (ROLE_EMPLOYEE/ROLE_ADMIN/SUPERVISOR) i nezasticeni interni pozivi (bez auth
     * konteksta — unit testovi/scheduleri) prolaze.
     */
    private void assertTransferAccessibleByCaller(Transfer transfer) {
        if (isCallerEmployeeOrAdmin()) return;
        Client client = currentClientOrNull();
        if (client == null) return; // no-auth / interni — ne diramo
        Long clientId = client.getId();
        boolean isCreator = transfer.getCreatedBy() != null
                && transfer.getCreatedBy().getId() != null
                && transfer.getCreatedBy().getId().equals(clientId);
        boolean ownsFrom = transfer.getFromAccount() != null
                && transfer.getFromAccount().getClient() != null
                && transfer.getFromAccount().getClient().getId().equals(clientId);
        boolean ownsTo = transfer.getToAccount() != null
                && transfer.getToAccount().getClient() != null
                && transfer.getToAccount().getClient().getId().equals(clientId);
        if (!isCreator && !ownsFrom && !ownsTo) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Transfer ne pripada korisniku.");
        }
    }

    private boolean isCallerEmployeeOrAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_ADMIN".equals(role) || "ROLE_EMPLOYEE".equals(role)
                    || "ADMIN".equals(role) || "EMPLOYEE".equals(role) || "SUPERVISOR".equals(role);
        });
    }

    private Client currentClientOrNull() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getName() == null) return null;
            return clientRepository.findByEmail(auth.getName()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- Helpers ----------

    private void ensureAccess(Client actor, Account account) {
        if (actor == null) throw new TransferAuthException("Authenticated client not found");

        if (account.getClient() != null && account.getClient().getId().equals(actor.getId())) {
            return;
        }
        if (account.getCompany() != null && account.getCompany().getAuthorizedPersons() != null) {
            boolean authorized = account.getCompany().getAuthorizedPersons().stream()
                    .anyMatch(ap -> ap.getClient() != null && ap.getClient().getId().equals(actor.getId()));
            if (authorized) return;
        }
        throw new org.springframework.security.access.AccessDeniedException("You do not have access to the specified account");
    }

    /**
     * P1-authz-idor-1 (R2 1371): interni transfer je SAMO izmedju racuna istog
     * vlasnika (Sc17 — premestanje sopstvenih sredstava bez provizije/limita).
     * Bez ove provere {@code ensureAccess} pojedinacno dozvoljava i ovlascenom
     * licu firme da "internim" transferom (bez provizije/limita) prebaci firmin
     * novac sa firminog racuna na svoj licni racun — zaobilazi placanje (koje
     * ima proviziju + dnevni limit). Tu deli istog vlasnika racuni moraju imati:
     * isti {@code client} ILI istu {@code company}.
     */
    private void ensureSameOwner(Account fromAccount, Account toAccount) {
        Long fromClientId = fromAccount.getClient() != null ? fromAccount.getClient().getId() : null;
        Long toClientId = toAccount.getClient() != null ? toAccount.getClient().getId() : null;
        if (fromClientId != null && fromClientId.equals(toClientId)) {
            return;
        }
        Long fromCompanyId = fromAccount.getCompany() != null ? fromAccount.getCompany().getId() : null;
        Long toCompanyId = toAccount.getCompany() != null ? toAccount.getCompany().getId() : null;
        if (fromCompanyId != null && fromCompanyId.equals(toCompanyId)) {
            return;
        }
        throw new org.springframework.security.access.AccessDeniedException(
                "Interni transfer je dozvoljen samo izmedju racuna istog vlasnika. "
                        + "Za prenos na tudji racun koristite placanje.");
    }

    private Client getAuthenticatedClient() {
        String email = getAuthenticatedEmail();
        return clientRepository.findByEmail(email).orElseThrow(() ->
                new TransferAuthException("Client not found for authenticated user"));
    }

    private String getAuthenticatedEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new TransferAuthException("User is not authenticated");
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        if (principal instanceof String s) {
            return s;
        }
        throw new TransferAuthException("Unable to resolve user email");
    }
}
