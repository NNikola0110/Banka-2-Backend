package rs.raf.banka2_bek.notification.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.auth.service.JwtService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.notification.model.Notification;
import rs.raf.banka2_bek.notification.model.NotificationType;
import rs.raf.banka2_bek.notification.repository.NotificationRepository;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationControllerIntegrationTest {

    private static final String EMAIL = "marko@test.rs";

    @Value("${local.server.port}")
    private int port;

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private DataSource dataSource;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Client client;
    private String bearerToken;

    @BeforeEach
    void setUp() {
        IntegrationTestCleanup.truncateAllTables(dataSource);

        Client newClient = new Client();
        newClient.setFirstName("Marko");
        newClient.setLastName("Markovic");
        newClient.setEmail(EMAIL);
        newClient.setPassword("x");
        newClient.setSaltPassword("dummy-salt");
        newClient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        newClient.setGender("M");
        newClient.setAddress("Test");
        newClient.setPhone("0600000000");
        newClient.setActive(true);
        client = clientRepository.save(newClient);

        User user = new User();
        user.setFirstName("Marko");
        user.setLastName("Markovic");
        user.setEmail(EMAIL);
        user.setPassword("x");
        user.setActive(true);
        user.setRole("CLIENT");
        userRepository.save(user);
        bearerToken = jwtService.generateAccessToken(user);
    }

    private Notification savedNotification(Long recipientId, boolean read, String title) {
        return notificationRepository.save(Notification.builder()
                .recipientId(recipientId)
                .recipientType("CLIENT")
                .notificationType(NotificationType.GENERAL)
                .title(title)
                .body("Telo poruke")
                .read(read)
                .build());
    }

    private HttpResponse<String> send(String method, String path, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authenticated) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getMyNotifications_returnsOnlyOwnNotifications() throws Exception {
        savedNotification(client.getId(), false, "Moja A");
        savedNotification(client.getId(), true, "Moja B");
        savedNotification(999L, false, "Tudja");

        HttpResponse<String> response = send("GET", "/notifications", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"totalElements\":2");
        assertThat(response.body()).contains("Moja A").contains("Moja B");
        assertThat(response.body()).doesNotContain("Tudja");
    }

    @Test
    void getMyNotifications_onlyUnreadFiltersOutReadOnes() throws Exception {
        savedNotification(client.getId(), false, "Neprocitana");
        savedNotification(client.getId(), true, "Procitana");

        HttpResponse<String> response = send("GET", "/notifications?onlyUnread=true", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"totalElements\":1");
        assertThat(response.body()).contains("Neprocitana").doesNotContain("Procitana");
    }

    @Test
    void unreadCount_reflectsMarkOneRead() throws Exception {
        Notification first = savedNotification(client.getId(), false, "A");
        savedNotification(client.getId(), false, "B");

        HttpResponse<String> before = send("GET", "/notifications/unread-count", true);
        assertThat(before.statusCode()).isEqualTo(200);
        assertThat(before.body()).contains("\"count\":2}");

        HttpResponse<String> markRead = send("PATCH", "/notifications/" + first.getId() + "/read", true);
        assertThat(markRead.statusCode()).isEqualTo(200);
        assertThat(markRead.body()).contains("\"read\":true");

        HttpResponse<String> after = send("GET", "/notifications/unread-count", true);
        assertThat(after.body()).contains("\"count\":1}");
    }

    @Test
    void markOneRead_foreignNotificationIsForbidden() throws Exception {
        Notification foreign = savedNotification(999L, false, "Tudja");

        HttpResponse<String> response = send("PATCH", "/notifications/" + foreign.getId() + "/read", true);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void markAllRead_returnsNoContentAndClearsUnreadCount() throws Exception {
        savedNotification(client.getId(), false, "A");
        savedNotification(client.getId(), false, "B");

        HttpResponse<String> response = send("PATCH", "/notifications/read-all", true);
        assertThat(response.statusCode()).isEqualTo(204);

        HttpResponse<String> count = send("GET", "/notifications/unread-count", true);
        assertThat(count.body()).contains("\"count\":0}");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        // P1-error-contract-1: GlobalSecurityConfig sada ima custom
        // authenticationEntryPoint → neautentifikovan zahtev na zasticenoj ruti
        // vraca 401 (ne vise Spring-default 403-prazno) sa JSON {"message":...} telom.
        // FE interceptor refresh-uje token SAMO na 401, pa je 401 ispravan kod.
        HttpResponse<String> response = send("GET", "/notifications", false);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"message\"");
    }
}
