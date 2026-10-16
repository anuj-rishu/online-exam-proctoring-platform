package in.srminsider.labs.shared;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cloudflare Realtime's TURN service issues short-lived credentials from a
 * server-side call (Turn Key ID + API Token) rather than handing out one
 * static username/password pair — the client never sees the API token
 * itself. This mints a credential, caches it for most of its TTL, and
 * transparently refreshes it; callers just ask for "the current one."
 */
@Slf4j
@Service
public class CloudflareTurnService {

    private static final String GENERATE_URL_TEMPLATE =
            "https://rtc.live.cloudflare.com/v1/turn/keys/%s/credentials/generate";

    /** Requested credential lifetime. Cloudflare allows up to 2,592,000s (30d). */
    private static final long TTL_SECONDS = 21_600; // 6 hours

    /** Refresh this long before the credential actually expires. */
    private static final long REFRESH_BUFFER_MILLIS = 30 * 60 * 1000; // 30 minutes

    @Value("${cloudflare.turn.key-id:}")
    private String keyId;

    @Value("${cloudflare.turn.api-token:}")
    private String apiToken;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Map<String, Object> cachedIceServer;
    private volatile long expiresAtMillis = 0;

    /**
     * Returns a fresh TURN ICE server entry ({urls, username, credential}),
     * or empty if no key is configured or Cloudflare's API can't be reached
     * (callers should just fall back to STUN-only rather than fail).
     */
    public synchronized Optional<Map<String, Object>> getTurnIceServer() {
        if (keyId == null || keyId.isBlank() || apiToken == null || apiToken.isBlank()) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        if (cachedIceServer != null && now < expiresAtMillis - REFRESH_BUFFER_MILLIS) {
            return Optional.of(cachedIceServer);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(GENERATE_URL_TEMPLATE, keyId)))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"ttl\":" + TTL_SECONDS + "}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                log.warn("[CloudflareTurn] credential generation failed: HTTP {} — {}",
                        response.statusCode(), response.body());
                return Optional.ofNullable(cachedIceServer);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode ice = root.get("iceServers");
            if (ice == null) {
                log.warn("[CloudflareTurn] unexpected response shape: {}", response.body());
                return Optional.ofNullable(cachedIceServer);
            }

            List<String> urls = objectMapper.convertValue(ice.get("urls"), new TypeReference<List<String>>() {});
            String username = ice.get("username").asText();
            String credential = ice.get("credential").asText();

            cachedIceServer = Map.of("urls", urls, "username", username, "credential", credential);
            expiresAtMillis = now + TTL_SECONDS * 1000L;
            return Optional.of(cachedIceServer);
        } catch (Exception e) {
            log.warn("[CloudflareTurn] failed to fetch TURN credentials, falling back to STUN-only", e);
            return Optional.ofNullable(cachedIceServer);
        }
    }
}
