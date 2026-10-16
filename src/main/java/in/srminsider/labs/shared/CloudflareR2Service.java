package in.srminsider.labs.shared;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
public class CloudflareR2Service {

    @Value("${cloudflare.r2.access-key}")
    private String accessKey;

    @Value("${cloudflare.r2.secret-key}")
    private String secretKey;

    @Value("${cloudflare.r2.endpoint}")
    private String endpoint;

    @Value("${cloudflare.r2.bucket}")
    private String bucket;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    @PostConstruct
    public void init() {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.warn("[R2] cloudflare.r2.access-key/secret-key are not set — photo upload/retrieval "
                    + "will be unavailable until R2_ACCESS_KEY/R2_SECRET_KEY are configured. "
                    + "Not failing startup so the rest of the app remains usable in dev.");
            return;
        }

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("auto"))
                .build();

        this.s3Presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("auto"))
                .build();
    }

    public String uploadBase64Image(String base64Image) {
        if (s3Client == null) {
            throw new IllegalStateException("R2 storage is not configured (missing access/secret key).");
        }
        try {
            String[] parts = base64Image.split(",");
            String imageData = parts.length > 1 ? parts[1] : parts[0];
            String mimeType = parts.length > 1 ? parts[0].split(";")[0].split(":")[1] : "image/jpeg";
            String extension = mimeType.split("/")[1];

            byte[] decodedBytes = Base64.getDecoder().decode(imageData);
            String objectKey = UUID.randomUUID().toString() + "." + extension;

            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(mimeType)
                    .build();

            s3Client.putObject(putOb, RequestBody.fromBytes(decodedBytes));

            return objectKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload image to R2: " + e.getMessage(), e);
        }
    }

    public String generatePresignedUrl(String objectKey) {
        if (objectKey == null || objectKey.isEmpty()) {
            return null;
        }
        if (s3Presigner == null) {
            throw new IllegalStateException("R2 storage is not configured (missing access/secret key).");
        }

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build();

            GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(60))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedGetObjectRequest = s3Presigner.presignGetObject(getObjectPresignRequest);
            return presignedGetObjectRequest.url().toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL: " + e.getMessage(), e);
        }
    }
}
