package rs.raf.banka2_bek.interbank.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.protocol.Message;
import rs.raf.banka2_bek.interbank.protocol.MessageType;
import rs.raf.banka2_bek.interbank.protocol.OtcNegotiation;
import rs.raf.banka2_bek.interbank.protocol.OtcOffer;
import rs.raf.banka2_bek.interbank.protocol.PublicStock;
import rs.raf.banka2_bek.interbank.protocol.UserInformation;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InterbankClient {

    private final InterbankProperties interbankProperties;
    private final BankRoutingService bankRoutingService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /** Strips any trailing slash from a base URL so concatenated paths are correct. */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public <Req, Resp> Resp sendMessage(int targetRoutingNumber,
                                         MessageType type,
                                         Message<Req> envelope,
                                         Class<Resp> responseType) {

        InterbankProperties.PartnerBank partnerBank = bankRoutingService.resolvePartnerByRouting(targetRoutingNumber)
                .orElseThrow( () -> new InterbankExceptions.InterbankProtocolException(
                        "Target routing number " + targetRoutingNumber + " could not be resolved."
                ));

        try {
            String serializedEnvelope = objectMapper.writeValueAsString(envelope);
            ResponseEntity<String> response = restClient
                    .post()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + "/interbank")
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(serializedEnvelope)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() ||
                            status.is5xxServerError(), (request, res) -> {
                        if (res.getStatusCode().value() == 401)
                            throw new InterbankExceptions.InterbankAuthException(
                                    "Invalid API key for routing " + targetRoutingNumber + ".");
                        throw new InterbankExceptions.InterbankCommunicationException(
                                "HTTP " + res.getStatusCode().value() + " from routing number " + targetRoutingNumber
                        );
                    })
                    .toEntity(String.class);

            int statusCode = response.getStatusCode().value();

            if (statusCode == 202 || responseType == Void.class)
                return null;

            if (statusCode == 200) {
                try {
                    return objectMapper.readValue(response.getBody(), responseType);
                } catch (JsonProcessingException e) {
                    throw new InterbankExceptions.InterbankProtocolException(
                            "Response could not be deserialized " + e.getMessage() + "."
                    );
                }
            }
            return null;
        }
        catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException("Envelope serialization failed with message : " + e.getMessage());
        }
        catch (RestClientException e) {
            // Sirov transport fail (connection refused / read-timeout / DNS) iz
            // .toEntity(). Bez ovog catch-a propagira kao bare RestClientException
            // koji sendPhase1Network NE hvata (hvata samo InterbankException) —
            // pa rollbackLocal nikad ne pozove i senderova rezervacija ostaje
            // zauvek zakljucana. Mapiramo u InterbankCommunicationException (extends
            // InterbankException) → tretira se kao NO/abort glas i radi se pravi
            // rollback. Isti ugovor kao ostale client metode (fetchPublicStocks itd.).
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error sending " + type + " to routing " + targetRoutingNumber
                            + ": " + e.getMessage());
        }
    }

    /**
     * §3.1 — GET /public-stock. Vraca sve javne ponude akcija u datoj banci.
     * Cherry-pick T5 (PR #71, stasadragovic) — implementacija iz Stasa-ine HEAD strane konflikta,
     * adaptirana za shared {@code restClient} bean iz {@link InterbankProperties} stila u sendMessage.
     */
    public List<PublicStock> fetchPublicStocks(int routingNumber) {
        InterbankProperties.PartnerBank partnerBank = resolvePartner(routingNumber);
        try {
            return restClient
                    .get()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + "/public-stock")
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                if (res.getStatusCode().value() == 401)
                                    throw new InterbankExceptions.InterbankAuthException(
                                            "Invalid API key for routing " + routingNumber + ".");
                                throw new InterbankExceptions.InterbankCommunicationException(
                                        "HTTP " + res.getStatusCode().value()
                                                + " from routing number " + routingNumber + " on /public-stock");
                            })
                    .body(new ParameterizedTypeReference<List<PublicStock>>() {});
        } catch (InterbankExceptions.InterbankException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error contacting routing " + routingNumber + " on /public-stock: " + e.getMessage(), e);
        }
    }

    /**
     * §3.2 — POST /negotiations. Inicira pregovor (prvi turn — kupac salje ponudu prodavcu).
     * Telo je {@link OtcOffer}; odgovor je {@link ForeignBankId} (id pregovora kod prodavca).
     */
    public ForeignBankId postNegotiation(int routingNumber, OtcOffer offer) {
        InterbankProperties.PartnerBank partnerBank = resolvePartner(routingNumber);
        try {
            return restClient
                    .post()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + "/negotiations")
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(offer)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                if (res.getStatusCode().value() == 401)
                                    throw new InterbankExceptions.InterbankAuthException(
                                            "Invalid API key for routing " + routingNumber + ".");
                                throw new InterbankExceptions.InterbankCommunicationException(
                                        "HTTP " + res.getStatusCode().value()
                                                + " from routing number " + routingNumber + " on /negotiations POST");
                            })
                    .body(ForeignBankId.class);
        } catch (InterbankExceptions.InterbankException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error posting negotiation to routing " + routingNumber + ": " + e.getMessage(), e);
        }
    }

    /**
     * §3.3 — PUT /negotiations/{rn}/{id}. Kontraponuda. Pozivac mora biti strana cija je tura
     * (proverava se preko {@code lastModifiedBy} polja u OtcOffer-u).
     *
     * <p>Defaultno, partner banka se resolve-uje po {@code negotiationId.routingNumber()}
     * (autoritativna banka — sluciaj BUYER counter na SELLER-authoritative pregovor).
     */
    public void putCounterOffer(ForeignBankId negotiationId, OtcOffer offer) {
        putCounterOffer(negotiationId, negotiationId.routingNumber(), offer);
    }

    /**
     * T2-J overload (Tim 1 cross-bank Stage C, 2026-05-20): PUT counter sa
     * EKSPLICITNIM targetPartnerRouting-om. Koristimo kad smo MI autoritativni
     * (negotiationId.routingNumber == myRouting) — onda treba slati PUT ka
     * partnerovom mirror-u (BUYER bank), a URL path zadrzava nas authoritative
     * {rn, id} (Tim 1 prepoznaje da je {222, negId} njihov mirror i azurira ga).
     *
     * <p>Bez ovog overload-a, T2-H je skipovao outbound u tom slucaju jer je
     * default resolve gadjao {@code routingNumber=222} (samima sebi → "Target
     * routing 222 could not be resolved"). T2-J razdvaja "ko je vlasnik
     * negotiationId-a" (URL path) od "kome saljemo HTTP" (partnerRouting).
     */
    public void putCounterOffer(ForeignBankId negotiationId, int targetPartnerRouting, OtcOffer offer) {
        InterbankProperties.PartnerBank partnerBank = resolvePartner(targetPartnerRouting);
        try {
            restClient
                    .put()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + "/negotiations/{rn}/{id}",
                            negotiationId.routingNumber(), negotiationId.id())
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(offer)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                int sc = res.getStatusCode().value();
                                if (sc == 401) throw new InterbankExceptions.InterbankAuthException(
                                        "Invalid API key for routing " + negotiationId.routingNumber() + ".");
                                if (sc == 404) throw new InterbankExceptions.InterbankNegotiationNotFoundException(
                                        partnerMissingNegotiation(partnerBank));
                                if (sc == 409) throw new InterbankExceptions.InterbankNegotiationConflictException(
                                        "Negotiation " + negotiationId + " conflict: not your turn or already closed.");
                                if (sc == 400) throw new InterbankExceptions.InterbankProtocolException(
                                        partnerRejected(partnerBank, "kontraponudu"));
                                throw new InterbankExceptions.InterbankCommunicationException(
                                        "HTTP " + sc + " from negotiation " + negotiationId + " on PUT");
                            })
                    .toBodilessEntity();
        } catch (InterbankExceptions.InterbankException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error on counter-offer to " + negotiationId + ": " + e.getMessage(), e);
        }
    }

    /**
     * §3.4 — GET /negotiations/{rn}/{id}. Cita trenutno stanje pregovora (autoritativna kopija je
     * uvek kod prodavceve banke; kupac poziva ovaj metod periodicno za sync).
     */
    public OtcNegotiation getNegotiation(ForeignBankId negotiationId) {
        InterbankProperties.PartnerBank partnerBank = resolvePartner(negotiationId.routingNumber());
        try {
            return restClient
                    .get()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + "/negotiations/{rn}/{id}",
                            negotiationId.routingNumber(), negotiationId.id())
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                int sc = res.getStatusCode().value();
                                if (sc == 401) throw new InterbankExceptions.InterbankAuthException(
                                        "Invalid API key for routing " + negotiationId.routingNumber() + ".");
                                if (sc == 404) throw new InterbankExceptions.InterbankNegotiationNotFoundException(
                                        partnerMissingNegotiation(partnerBank));
                                if (sc == 409) throw new InterbankExceptions.InterbankNegotiationConflictException(
                                        "Negotiation " + negotiationId + " conflict on GET.");
                                if (sc == 400) throw new InterbankExceptions.InterbankProtocolException(
                                        partnerRejected(partnerBank, "citanje pregovora"));
                                throw new InterbankExceptions.InterbankCommunicationException(
                                        "HTTP " + sc + " from negotiation " + negotiationId + " on GET");
                            })
                    .body(OtcNegotiation.class);
        } catch (InterbankExceptions.InterbankException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error reading negotiation " + negotiationId + ": " + e.getMessage(), e);
        }
    }

    /**
     * §3.5 — DELETE /negotiations/{rn}/{id}. Zatvara pregovor (status: ne-prihvacen).
     * Idempotentno: ponovno DELETE-ovanje istog id-a vraca 204.
     */
    public void deleteNegotiation(ForeignBankId negotiationId) {
        deleteNegotiation(negotiationId, negotiationId.routingNumber());
    }

    /**
     * T2-J overload: DELETE sa EKSPLICITNIM targetPartnerRouting-om (analogno
     * {@link #putCounterOffer(ForeignBankId, int, OtcOffer)}). Koristimo kad smo
     * MI autoritativni — DELETE ka partnerovom mirror-u, URL path zadrzava
     * authoritative {rn, id}.
     */
    public void deleteNegotiation(ForeignBankId negotiationId, int targetPartnerRouting) {
        InterbankProperties.PartnerBank partnerBank = resolvePartner(targetPartnerRouting);
        try {
            restClient
                    .delete()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + "/negotiations/{rn}/{id}",
                            negotiationId.routingNumber(), negotiationId.id())
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                int sc = res.getStatusCode().value();
                                if (sc == 401) throw new InterbankExceptions.InterbankAuthException(
                                        "Invalid API key for routing " + negotiationId.routingNumber() + ".");
                                if (sc == 404) throw new InterbankExceptions.InterbankNegotiationNotFoundException(
                                        partnerMissingNegotiation(partnerBank));
                                if (sc == 409) throw new InterbankExceptions.InterbankNegotiationConflictException(
                                        "Negotiation " + negotiationId + " conflict on DELETE.");
                                if (sc == 400) throw new InterbankExceptions.InterbankProtocolException(
                                        partnerRejected(partnerBank, "zatvaranje pregovora"));
                                throw new InterbankExceptions.InterbankCommunicationException(
                                        "HTTP " + sc + " from negotiation " + negotiationId + " on DELETE");
                            })
                    .toBodilessEntity();
        } catch (InterbankExceptions.InterbankException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error deleting negotiation " + negotiationId + ": " + e.getMessage(), e);
        }
    }

    /**
     * §3.6 — GET /negotiations/{rn}/{id}/accept. SINHRONO ceka da partnerova banka commit-uje
     * transakciju (200 = COMMITTED). Ovo je jedini sinhroni poziv u protokolu — partnerska
     * banka drzi konekciju otvorenu dok ne zavrsi 2PC interno.
     */
    public void acceptNegotiation(ForeignBankId negotiationId) {
        InterbankProperties.PartnerBank partnerBank = resolvePartner(negotiationId.routingNumber());
        try {
            restClient
                    .get()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + "/negotiations/{rn}/{id}/accept",
                            negotiationId.routingNumber(), negotiationId.id())
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                int sc = res.getStatusCode().value();
                                if (sc == 401) throw new InterbankExceptions.InterbankAuthException(
                                        "Invalid API key for routing " + negotiationId.routingNumber() + ".");
                                if (sc == 404) throw new InterbankExceptions.InterbankNegotiationNotFoundException(
                                        partnerMissingNegotiation(partnerBank));
                                if (sc == 409) throw new InterbankExceptions.InterbankNegotiationConflictException(
                                        "Negotiation " + negotiationId + " conflict on accept.");
                                if (sc == 400) throw new InterbankExceptions.InterbankProtocolException(
                                        partnerRejected(partnerBank, "prihvatanje ponude"));
                                throw new InterbankExceptions.InterbankCommunicationException(
                                        "HTTP " + sc + " from negotiation " + negotiationId + " on accept");
                            })
                    .toBodilessEntity();
        } catch (InterbankExceptions.InterbankException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error accepting negotiation " + negotiationId + ": " + e.getMessage(), e);
        }
    }

    /**
     * §3.7 — GET /user/{rn}/{id}. Razresavanje friendly imena za opaque foreign user id.
     * Koristi se za UI prikaz (umesto opaque id-a). 404 nije fatalno — samo nedostatak imena.
     */
    public UserInformation getUserInfo(ForeignBankId userId) {
        InterbankProperties.PartnerBank partnerBank = resolvePartner(userId.routingNumber());
        // §3.7 path je per-partner konfigurabilan: spec default je "/user/{rn}/{id}",
        // ali Tim 1 ima override "/interbank/user/{rn}/{id}" (path collision sa FE rutom).
        String pathTemplate = partnerBank.getUserInfoPath();
        if (pathTemplate == null || pathTemplate.isBlank()) {
            pathTemplate = "/user/{rn}/{id}";
        }
        try {
            return restClient
                    .get()
                    .uri(normalizeBaseUrl(partnerBank.getBaseUrl()) + pathTemplate,
                            userId.routingNumber(), userId.id())
                    .header("X-Api-Key", partnerBank.getOutboundToken())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                if (res.getStatusCode().value() == 401)
                                    throw new InterbankExceptions.InterbankAuthException(
                                            "Invalid API key for routing " + userId.routingNumber() + ".");
                                // §3.7 — 404 nije fatalno, samo nedostatak imena. Bacamo poseban tip
                                // da caller moze graceful fallback (prikazi opaque id u UI-u).
                                if (res.getStatusCode().value() == 404)
                                    throw new InterbankExceptions.InterbankUserNotFoundException(
                                            "User " + userId + " not found at partner bank");
                                throw new InterbankExceptions.InterbankCommunicationException(
                                        "HTTP " + res.getStatusCode().value()
                                                + " from user " + userId + " on GET");
                            })
                    .body(UserInformation.class);
        } catch (InterbankExceptions.InterbankException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InterbankExceptions.InterbankCommunicationException(
                    "Network error fetching user info " + userId + ": " + e.getMessage(), e);
        }
    }

    private InterbankProperties.PartnerBank resolvePartner(int routingNumber) {
        return bankRoutingService.resolvePartnerByRouting(routingNumber)
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Target routing number " + routingNumber + " could not be resolved."
                ));
    }

    /**
     * bagovi-fix-2 follow-up: kad partnerska banka odbije nas (protokol-korektan)
     * outbound zahtev svojim HTTP 400, OVU poruku krajnji korisnik vidi u toast-u
     * (FE {@code getErrorMessage} -> {@code response.data.message}, a wrapper handler
     * mapira {@code InterbankProtocolException} -> 400). Zato je user-friendly i jasno
     * atribuira gresku PARTNERU (ne nasoj validaciji), uz partnerovo display ime.
     * Ranije je poruka bila sirov tehnicki string ("Bad request on accept negotiation
     * 265:...") koji izgleda kao NAS bug.
     */
    private static String partnerRejected(
            InterbankProperties.PartnerBank partnerBank, String akcija) {
        String subjekt = (partnerBank != null && partnerBank.getDisplayName() != null
                && !partnerBank.getDisplayName().isBlank())
                ? partnerBank.getDisplayName() : "Partnerska banka";
        return subjekt + " je odbila " + akcija
                + " (greska na njihovoj strani). Pregovor je mozda istekao ili je vec zatvoren.";
    }

    /**
     * bagovi-fix-2 follow-up: partner vrati HTTP 404 na nas outbound negotiation
     * zahtev (PUT/GET/DELETE/accept) jer NEMA mirror tog pregovora kod sebe
     * (istekao / nikad kreiran / zatvoren, ili ne prepoznaje nas autoritativni
     * mirror kljuc). Ovu poruku korisnik vidi u FE toast-u (GlobalExceptionHandler
     * mapira {@code InterbankNegotiationNotFoundException} -> 404 + propagira poruku),
     * pa je user-friendly i partner-atribuirana umesto sirovog
     * "Negotiation 222:... not found at partner bank.".
     */
    private static String partnerMissingNegotiation(InterbankProperties.PartnerBank partnerBank) {
        String subjekt = (partnerBank != null && partnerBank.getDisplayName() != null
                && !partnerBank.getDisplayName().isBlank())
                ? partnerBank.getDisplayName() : "Partnerska banka";
        return subjekt + " ne prepoznaje ovaj pregovor (nema ga na njihovoj strani). "
                + "Verovatno je istekao ili je vec zatvoren — pokrenite nov.";
    }
}
