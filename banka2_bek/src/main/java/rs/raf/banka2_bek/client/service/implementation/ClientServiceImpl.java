package rs.raf.banka2_bek.client.service.implementation;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.auth.exception.EmailAlreadyExistsException;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.client.dto.ClientResponseDto;
import rs.raf.banka2_bek.client.dto.CreateClientRequestDto;
import rs.raf.banka2_bek.client.dto.UpdateClientRequestDto;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.client.service.ClientService;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientServiceImpl implements ClientService {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public ClientResponseDto createClient(CreateClientRequestDto request) {
        // R5 1884: duplikat email = 409 Conflict (ne bare RuntimeException→400).
        // Pre-check je best-effort UX; pravi TOCTOU guard je DB unique constraint na
        // email (User/Client) → globalni DataIntegrityViolationException→409 handler.
        if (clientRepository.findByEmail(request.getEmail()).isPresent()
                || userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException("Klijent sa ovim emailom vec postoji");
        }

        String password = (request.getPassword() != null && !request.getPassword().isBlank())
                ? request.getPassword()
                : UUID.randomUUID().toString().substring(0, 12);

        // BE-AUTH-06 fix: salt + concat pattern je DEPRECATED. Login flow proverava
        // samo User.password (bez salt-a). Client.password ostaje zbog nullable=false
        // constraint-a — popunjava se istim bcrypt hash-om kao User.password
        // (umesto razlicitog salt-concat hash-a) tako da je single source of truth.
        // Salt polje takodje deprecated — placeholder "deprecated" string.
        String hashedPassword = passwordEncoder.encode(password);

        Client client = Client.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender())
                .email(request.getEmail())
                .phone(request.getPhone())
                .address(request.getAddress())
                // DEPRECATED polja — vidi Client.java javadoc.
                // Cuvamo istu vrednost kao User.password radi konzistentnosti.
                .password(hashedPassword)
                .saltPassword("deprecated")
                .active(true)
                .build();

        client = clientRepository.save(client);

        // Login flow koristi User.password kao single source of truth.
        if (userRepository.findByEmail(request.getEmail()).isEmpty()) {
            User user = new User();
            user.setFirstName(request.getFirstName());
            user.setLastName(request.getLastName());
            user.setEmail(request.getEmail());
            user.setPassword(hashedPassword);
            user.setPhone(request.getPhone());
            user.setAddress(request.getAddress());
            user.setActive(true);
            user.setRole("CLIENT");
            userRepository.save(user);
        }

        return toResponse(client);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ClientResponseDto> getClients(int page, int limit, String firstName, String lastName, String email, String search) {
        PageRequest pageRequest = PageRequest.of(page, limit, Sort.by("lastName").ascending());
        if (search != null && !search.isBlank()) {
            return clientRepository.findByUnifiedSearch(search.trim(), pageRequest)
                    .map(this::toResponse);
        }
        return clientRepository.findByFilters(firstName, lastName, email, pageRequest)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public ClientResponseDto getClientById(Long id) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Klijent sa ID " + id + " nije pronadjen"));
        return toResponse(client);
    }

    @Override
    @Transactional(readOnly = true)
    public ClientResponseDto getClientByEmail(String email) {
        Client client = clientRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Klijent sa email-om " + email + " nije pronadjen"));
        return toResponse(client);
    }

    @Override
    @Transactional
    public ClientResponseDto updateClient(Long id, UpdateClientRequestDto request) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Klijent sa ID " + id + " nije pronadjen"));

        // C2 Sc40 (§433): "Prilikom menjanja mejla treba proveriti da je mejl i dalje
        // unique u bazi." Email se ranije TIHO ignorisao (DTO polje postojalo, ali se
        // nikad nije citalo). Sada: ako stigne nov email koji se RAZLIKUJE od trenutnog,
        // proveravamo jedinstvenost preko OBE tabele (clients + users), i sinhronizujemo
        // promenu na povezani User. Duplikat → EmailAlreadyExistsException → 409 Conflict.
        String oldEmail = client.getEmail();
        boolean emailChanged = false;
        String newEmail = request.getEmail() != null ? request.getEmail().trim() : null;
        if (newEmail != null && !newEmail.isBlank() && !newEmail.equalsIgnoreCase(oldEmail)) {
            // Uniqueness provera — drugi klijent ili bilo koji user sa tim email-om.
            final Long thisClientId = client.getId();
            boolean takenByClient = clientRepository.findByEmail(newEmail)
                    .filter(c -> !c.getId().equals(thisClientId))
                    .isPresent();
            boolean takenByUser = userRepository.findByEmail(newEmail)
                    .filter(u -> !u.getEmail().equalsIgnoreCase(oldEmail))
                    .isPresent();
            if (takenByClient || takenByUser) {
                throw new EmailAlreadyExistsException("Email " + newEmail + " je vec u upotrebi.");
            }
            client.setEmail(newEmail);
            emailChanged = true;
        }

        if (request.getFirstName() != null) client.setFirstName(request.getFirstName());
        if (request.getLastName() != null) client.setLastName(request.getLastName());
        if (request.getGender() != null) client.setGender(request.getGender());
        if (request.getPhone() != null) client.setPhone(request.getPhone());
        if (request.getAddress() != null) client.setAddress(request.getAddress());
        if (request.getDateOfBirth() != null) client.setDateOfBirth(request.getDateOfBirth());

        client = clientRepository.save(client);

        // Sync with users table. Linkovani User se nalazi po STAROM email-u (single
        // source of truth za login). Ako se email menjao, azuriramo i User.email da
        // login ostane konzistentan; DB unique constraint na users.email je
        // konacni TOCTOU guard (DataIntegrityViolationException → 409 globalni handler).
        final boolean emailChangedFinal = emailChanged;
        final String newEmailFinal = newEmail;
        userRepository.findByEmail(oldEmail).ifPresent(user -> {
            if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
            if (request.getLastName() != null) user.setLastName(request.getLastName());
            if (request.getPhone() != null) user.setPhone(request.getPhone());
            if (request.getAddress() != null) user.setAddress(request.getAddress());
            if (emailChangedFinal) user.setEmail(newEmailFinal);
            userRepository.save(user);
        });

        return toResponse(client);
    }

    @Override
    @Transactional
    public ClientResponseDto setTradingPermission(Long clientId, boolean canTrade) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Klijent sa ID " + clientId + " nije pronadjen"));
        client.setCanTradeStocks(canTrade);
        client = clientRepository.save(client);
        return toResponse(client);
    }

    private ClientResponseDto toResponse(Client client) {
        return ClientResponseDto.builder()
                .id(client.getId())
                .firstName(client.getFirstName())
                .lastName(client.getLastName())
                .dateOfBirth(client.getDateOfBirth())
                .gender(client.getGender())
                .email(client.getEmail())
                .phone(client.getPhone())
                .address(client.getAddress())
                .active(client.getActive())
                .canTradeStocks(client.getCanTradeStocks())
                .createdAt(client.getCreatedAt())
                .build();
    }
}
