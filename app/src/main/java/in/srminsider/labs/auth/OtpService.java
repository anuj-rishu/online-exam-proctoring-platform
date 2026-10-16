package in.srminsider.labs.auth;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;
import java.util.Map;

@Service
public class OtpService {

    private final Map<String, OtpDetails> otpCache = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public String generateOtp(String email) {
        String otp = String.format("%06d", random.nextInt(1000000));
        long expiryTime = System.currentTimeMillis() + 5 * 60 * 1000;
        otpCache.put(email, new OtpDetails(otp, expiryTime));
        return otp;
    }

    public boolean verifyOtp(String email, String otp) {
        OtpDetails details = otpCache.get(email);
        if (details == null) {
            return false;
        }
        if (System.currentTimeMillis() > details.expiryTime) {
            otpCache.remove(email);
            return false;
        }
        if (details.otp.equals(otp)) {
            otpCache.remove(email);
            return true;
        }
        return false;
    }

    private static class OtpDetails {
        String otp;
        long expiryTime;
        OtpDetails(String otp, long expiryTime) {
            this.otp = otp;
            this.expiryTime = expiryTime;
        }
    }
}
