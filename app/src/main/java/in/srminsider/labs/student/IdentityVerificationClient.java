package in.srminsider.labs.student;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;


@Slf4j
@Service
public class IdentityVerificationClient {

    @Value("${identity.verification.host:127.0.0.1}")
    private String host;

    @Value("${identity.verification.port:8500}")
    private int port;

    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(6000);
        return new RestTemplate(factory);
    }

    private String baseUrl() {
        return "http://" + host + ":" + port;
    }

    private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> checkPosition(String imageDataUrl) {
        try {
            return restTemplate.postForObject(
                    baseUrl() + "/position-check", jsonBody(Map.of("image", imageDataUrl)), Map.class);
        } catch (ResourceAccessException e) {
            log.warn("[IdentityVerification] Service unreachable for position-check", e);
            return unavailable();
        } catch (RestClientException e) {
            log.error("[IdentityVerification] position-check failed", e);
            return unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> checkIdCard(String imageDataUrl) {
        try {
            return restTemplate.postForObject(
                    baseUrl() + "/id-card-check", jsonBody(Map.of("image", imageDataUrl)), Map.class);
        } catch (ResourceAccessException e) {
            log.warn("[IdentityVerification] Service unreachable for id-card-check", e);
            return unavailable();
        } catch (RestClientException e) {
            log.error("[IdentityVerification] id-card-check failed", e);
            return unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyMatch(String facePhotoDataUrl, String idCardPhotoDataUrl) {
        try {
            return restTemplate.postForObject(
                    baseUrl() + "/verify-identity",
                    jsonBody(Map.of("facePhoto", facePhotoDataUrl, "idCardPhoto", idCardPhotoDataUrl)),
                    Map.class);
        } catch (ResourceAccessException e) {
            log.warn("[IdentityVerification] Service unreachable for verify-identity", e);
            return unavailable();
        } catch (RestClientException e) {
            log.error("[IdentityVerification] verify-identity failed", e);
            return unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> checkPose(String imageDataUrl) {
        try {
            return restTemplate.postForObject(
                    baseUrl() + "/pose-check", jsonBody(Map.of("image", imageDataUrl)), Map.class);
        } catch (ResourceAccessException e) {
            log.warn("[IdentityVerification] Service unreachable for pose-check", e);
            return unavailable();
        } catch (RestClientException e) {
            log.error("[IdentityVerification] pose-check failed", e);
            return unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> checkLiveFace(String imageDataUrl, List<Double> referenceEmbedding) {
        try {
            return restTemplate.postForObject(
                    baseUrl() + "/verify-live",
                    jsonBody(Map.of("image", imageDataUrl, "referenceEmbedding", referenceEmbedding)),
                    Map.class);
        } catch (ResourceAccessException e) {
            log.warn("[IdentityVerification] Service unreachable for verify-live", e);
            return unavailable();
        } catch (RestClientException e) {
            log.error("[IdentityVerification] verify-live failed", e);
            return unavailable();
        }
    }

    private Map<String, Object> unavailable() {
        return Map.of(
                "ready", false,
                "isMatch", false,
                "serviceUnavailable", true,
                "message", "Verification service is starting up or unavailable — please try again in a moment."
        );
    }
}
