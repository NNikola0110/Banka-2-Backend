package rs.raf.banka2_bek.client.service;

import org.springframework.data.domain.Page;
import rs.raf.banka2_bek.client.dto.ClientResponseDto;
import rs.raf.banka2_bek.client.dto.CreateClientRequestDto;
import rs.raf.banka2_bek.client.dto.UpdateClientRequestDto;

public interface ClientService {
    ClientResponseDto createClient(CreateClientRequestDto request);
    Page<ClientResponseDto> getClients(int page, int limit, String firstName, String lastName, String email, String search);

    /**
     * Backwards-compatible overload (no unified search param). Internally delegates to the 6-arg version with search=null.
     */
    default Page<ClientResponseDto> getClients(int page, int limit, String firstName, String lastName, String email) {
        return getClients(page, limit, firstName, lastName, email, null);
    }
    ClientResponseDto getClientById(Long id);
    ClientResponseDto getClientByEmail(String email);
    ClientResponseDto updateClient(Long id, UpdateClientRequestDto request);

    /**
     * T4A-017: supervizor menja `canTradeStocks` flag za klijenta. Spec C3 §6 / C4 §31
     * trazi da samo klijenti sa permisijom mogu da trgovuju hartijama (Berza + OTC).
     */
    ClientResponseDto setTradingPermission(Long clientId, boolean canTrade);
}
