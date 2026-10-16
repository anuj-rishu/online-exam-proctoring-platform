package in.srminsider.labs.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BrevoService {

    @Value("${brevo.api.key}")
    private String apiKey;

    @Value("${brevo.sender.email}")
    private String senderEmail;

    @Value("${exam.schedule.date.webdev-aiml}")
    private String examDateWebAi;

    @Value("${exam.schedule.time.webdev-aiml}")
    private String examTimeWebAi;

    @Value("${exam.schedule.date.cloud-appdev}")
    private String examDateCloudDev;

    @Value("${exam.schedule.time.cloud-appdev}")
    private String examTimeCloudDev;

    @Value("classpath:templates/email/otp_email.html")
    private Resource otpEmailResource;

    @Value("classpath:templates/email/admin_credentials_email.html")
    private Resource adminEmailResource;

    @Value("classpath:templates/email/student_credentials_email.html")
    private Resource studentEmailResource;

    private String otpEmailHtml;
    private String adminEmailHtml;
    private String studentEmailHtml;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";

    @PostConstruct
    public void init() {
        try {
            otpEmailHtml = StreamUtils.copyToString(otpEmailResource.getInputStream(), StandardCharsets.UTF_8);
            adminEmailHtml = StreamUtils.copyToString(adminEmailResource.getInputStream(), StandardCharsets.UTF_8);
            studentEmailHtml = StreamUtils.copyToString(studentEmailResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load email templates", e);
        }
    }

    public void sendOtpEmail(String toEmail, String otp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("api-key", apiKey);

        Map<String, Object> body = new HashMap<>();

        Map<String, String> sender = new HashMap<>();
        sender.put("name", "SRM Insider Labs");
        sender.put("email", senderEmail);
        body.put("sender", sender);

        Map<String, String> to = new HashMap<>();
        to.put("email", toEmail);
        body.put("to", Collections.singletonList(to));

        body.put("subject", "Admin Login OTP - SRM Insider Labs");

        String htmlContent = otpEmailHtml.replace("{{OTP}}", otp);

        body.put("htmlContent", htmlContent);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(BREVO_API_URL, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("Failed to send OTP email via Brevo. Status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println("Error sending email via Brevo: " + e.getMessage());
        }
    }

    // Credentials go into an HTML template, so a raw & or < would corrupt them in transit.
    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public void sendAdminCredentialsEmail(String toEmail, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("api-key", apiKey);

        Map<String, Object> body = new HashMap<>();

        Map<String, String> sender = new HashMap<>();
        sender.put("name", "SRM Insider Labs");
        sender.put("email", senderEmail);
        body.put("sender", sender);

        Map<String, String> to = new HashMap<>();
        to.put("email", toEmail);
        body.put("to", Collections.singletonList(to));

        body.put("subject", "Your Admin Credentials - SRM Insider Labs");

        String htmlContent = adminEmailHtml
                .replace("{{EMAIL}}", escapeHtml(toEmail))
                .replace("{{PASSWORD}}", escapeHtml(password));

        body.put("htmlContent", htmlContent);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(BREVO_API_URL, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("Failed to send Admin Credentials via Brevo. Status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println("Error sending email via Brevo: " + e.getMessage());
        }
    }

    public void sendStudentCredentialsEmail(String toEmail, String studentName, String password, Integer year, String domain) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("api-key", apiKey);

        Map<String, Object> body = new HashMap<>();

        Map<String, String> sender = new HashMap<>();
        sender.put("name", "SRM Insider Labs");
        sender.put("email", senderEmail);
        body.put("sender", sender);

        Map<String, String> to = new HashMap<>();
        to.put("email", toEmail);
        if (studentName != null && !studentName.isBlank()) {
            to.put("name", studentName);
        }
        body.put("to", Collections.singletonList(to));

        body.put("subject", "Your Student Assessment Portal Credentials - SRM Insider Labs");

        String yearStr = year != null ? "Year " + year : "1st Year";
        String domainStr = (domain != null && !domain.isBlank()) ? domain : "General / Web Dev";
        String displayName = (studentName != null && !studentName.isBlank()) ? studentName : "Student";

        String examDate = examDateWebAi;
        String examTime = examTimeWebAi;
        
        // Explicitly check against the exact domains defined in ProblemCatalog
        if ("Cloud".equalsIgnoreCase(domainStr) 
         || "App Dev".equalsIgnoreCase(domainStr) 
         || domainStr.toLowerCase().contains("devops")) {
            examDate = examDateCloudDev;
            examTime = examTimeCloudDev;
        }

        String htmlContent = studentEmailHtml
                .replace("{{DISPLAY_NAME}}", escapeHtml(displayName))
                .replace("{{EMAIL}}", escapeHtml(toEmail))
                .replace("{{PASSWORD}}", escapeHtml(password))
                .replace("{{DOMAIN}}", escapeHtml(domainStr))
                .replace("{{YEAR}}", escapeHtml(yearStr))
                .replace("{{EXAM_DATE}}", escapeHtml(examDate))
                .replace("{{EXAM_TIME}}", escapeHtml(examTime));

        body.put("htmlContent", htmlContent);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(BREVO_API_URL, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("Failed to send Student Credentials via Brevo. Status: " + response.getStatusCode());
            } else {
                System.out.println("✅ Sent student invitation email via Brevo to: " + toEmail);
            }
        } catch (Exception e) {
            System.err.println("Error sending student invitation email via Brevo: " + e.getMessage());
        }
    }
}
