package in.srminsider.labs.shared;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hands the browser its ICE server list instead of hardcoding it in static
 * JS, so a TURN relay can be added/rotated via config alone. Gated behind
 * plain authentication (any logged-in student or admin) rather than being
 * public, since minting a TURN credential is a real (if short-lived and
 * scoped) grant of access to that relay.
 */
@RestController
@RequestMapping("/api/webrtc")
@RequiredArgsConstructor
public class WebRtcConfigController {

    private final CloudflareTurnService turnService;

    @GetMapping("/ice-servers")
    public ResponseEntity<?> getIceServers() {
        List<Map<String, Object>> servers = new ArrayList<>();
        servers.add(Map.of("urls", "stun:stun.l.google.com:19302"));
        turnService.getTurnIceServer().ifPresent(servers::add);
        return ResponseEntity.ok(Map.of("iceServers", servers));
    }
}
