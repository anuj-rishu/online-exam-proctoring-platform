package in.srminsider.labs.proctoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.admin.ProctorAssignmentService;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.student.SessionCacheService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SignalingHandler extends TextWebSocketHandler {

    private static final String RELAY_TOPIC = "ws:relay";

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionCacheService sessionCache;
    private final RedissonClient redisson;
    private final ProctorAssignmentService assignmentService;

    private final String instanceId = UUID.randomUUID().toString();

    private RTopic relayTopic;
    private int relayListenerId;

    private static final Map<String, Long> SECTION_DURATIONS = Map.of(
        "Programming MCQ", 30L * 60L,
        "Aptitude MCQ", 5L * 60L,
        "Academics", 5L * 60L,
        "Programming Question", 40L * 60L
    );

    public SignalingHandler(SessionCacheService sessionCache, RedissonClient redisson,
                            ProctorAssignmentService assignmentService) {
        this.sessionCache = sessionCache;
        this.redisson = redisson;
        this.assignmentService = assignmentService;
    }

    @PostConstruct
    public void subscribeToRelay() {
        relayTopic = redisson.getTopic(RELAY_TOPIC);
        relayListenerId = relayTopic.addListener(String.class, (channel, envelopeJson) -> {
            try {
                fanOutLocally(objectMapper.readValue(envelopeJson, RelayEnvelope.class));
            } catch (Exception e) {
                log.error("[Signaling] Failed to handle relayed message", e);
            }
        });
        log.info("[Signaling] Instance {} subscribed to '{}'", instanceId, RELAY_TOPIC);
    }

    @PreDestroy
    public void unsubscribeFromRelay() {
        if (relayTopic != null) {
            relayTopic.removeListener(relayListenerId);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        log.info("New WebSocket connection established: {}", session.getId());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) payload.get("type");

        String authenticatedUser = (String) session.getAttributes().get("username");
        String authenticatedRole = (String) session.getAttributes().get("role");

        if ("join".equals(type) || "update_filter".equals(type) || "admin_ready".equals(type)) {
            if (payload.containsKey("year")) session.getAttributes().put("year", String.valueOf(payload.get("year")));
            if (payload.containsKey("domain")) session.getAttributes().put("domain", payload.get("domain"));
            if (payload.containsKey("filterYear")) session.getAttributes().put("filterYear", String.valueOf(payload.get("filterYear")));
            if (payload.containsKey("filterDomain")) session.getAttributes().put("filterDomain", payload.get("filterDomain"));
        }

        if ("section_start".equals(type) || "request_timer_sync".equals(type)) {
            String username = authenticatedUser;
            String section = (String) payload.get("section");
            Integer yearVal = payload.get("year") != null ? Integer.valueOf(String.valueOf(payload.get("year"))) : null;
            String domain = (String) payload.get("domain");

            if (username != null && section != null && yearVal != null && domain != null) {
                Optional<TestSession> opt = sessionCache.getSession(username, yearVal, domain);

                if (opt.isPresent()) {
                    TestSession testSession = opt.get();
                    long now = System.currentTimeMillis();
                    long totalSeconds = SECTION_DURATIONS.getOrDefault(section, 30L * 60L);

                    testSession.getSectionStartTimes().putIfAbsent(section, now);
                    long startTime = testSession.getSectionStartTimes().get(section);
                    long elapsedSeconds = (now - startTime) / 1000;
                    long remainingSeconds = Math.max(0, totalSeconds - elapsedSeconds);

                    List<String> locked = testSession.getLockedSections();
                    if (remainingSeconds <= 0 && !locked.contains(section)) {
                        locked.add(section);
                        testSession.getSectionEndTimes().putIfAbsent(section, startTime + (totalSeconds * 1000));
                    }

                    sessionCache.saveToCache(testSession);

                    Map<String, Object> response = new HashMap<>();
                    response.put("type", "timer_sync");
                    response.put("section", section);
                    response.put("totalSeconds", totalSeconds);
                    response.put("remainingSeconds", remainingSeconds);
                    response.put("isLocked", locked.contains(section));
                    response.put("lockedSections", locked);

                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                }
            }
            return;
        }

        if ("section_timeout".equals(type) || "section_lock".equals(type)) {
            String username = authenticatedUser;
            String section = (String) payload.get("section");
            Integer yearVal = payload.get("year") != null ? Integer.valueOf(String.valueOf(payload.get("year"))) : null;
            String domain = (String) payload.get("domain");

            if (username != null && section != null && yearVal != null && domain != null) {
                Optional<TestSession> opt = sessionCache.getSession(username, yearVal, domain);

                if (opt.isPresent()) {
                    TestSession testSession = opt.get();
                    if (!testSession.getLockedSections().contains(section)) {
                        testSession.getLockedSections().add(section);
                        testSession.getSectionEndTimes().putIfAbsent(section, System.currentTimeMillis());
                    }
                    sessionCache.saveToCache(testSession);

                    Map<String, Object> response = new HashMap<>();
                    response.put("type", "section_locked_ack");
                    response.put("section", section);
                    response.put("lockedSections", testSession.getLockedSections());
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                }
            }
            return;
        }

        if ("update_filter".equals(type)) return;

        payload.put("studentId", authenticatedUser);
        payload.put("username", authenticatedUser);
        payload.put("role", authenticatedRole);

        if ("snapshot".equals(type)) {
            if (!"student".equals(authenticatedRole)) return;
            if (!isValidSnapshot(payload.get("snapshot"))) {
                log.warn("[Signaling] Dropped malformed snapshot from {}", authenticatedUser);
                return;
            }
            sessionCache.recordSnapshot(authenticatedUser);
        }

        RelayEnvelope env = new RelayEnvelope();
        env.originInstanceId = instanceId;
        env.originSessionId = session.getId();
        env.type = type;
        env.target = (String) payload.get("target");
        env.payloadRole = authenticatedRole;
        env.senderRole = authenticatedRole;
        env.senderUsername = authenticatedUser;
        env.senderSuperAdmin = Boolean.TRUE.equals(session.getAttributes().get("superAdmin"));
        env.senderYear = (String) session.getAttributes().get("year");
        env.senderDomain = (String) session.getAttributes().get("domain");
        env.senderFilterYear = (String) session.getAttributes().get("filterYear");
        env.senderFilterDomain = (String) session.getAttributes().get("filterDomain");
        env.rawMessage = objectMapper.writeValueAsString(payload);

        redisson.getTopic(RELAY_TOPIC).publish(objectMapper.writeValueAsString(env));
    }

    private static final java.util.regex.Pattern SNAPSHOT_PATTERN =
            java.util.regex.Pattern.compile("^data:image/(jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$");

    private boolean isValidSnapshot(Object snapshot) {
        if (!(snapshot instanceof String value)) return false;
        if (value.length() > 400_000) return false;
        return SNAPSHOT_PATTERN.matcher(value).matches();
    }

    
    public void publishUnblockRequest(String studentId, Integer year, String domain) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "unblock_request");
            payload.put("studentId", studentId);
            payload.put("username", studentId);
            payload.put("year", year);
            payload.put("domain", domain);

            RelayEnvelope env = new RelayEnvelope();
            env.originInstanceId = instanceId;
            env.type = "unblock_request";
            env.payloadRole = "student";
            env.senderRole = "student";
            env.senderUsername = studentId;
            env.senderYear = String.valueOf(year);
            env.senderDomain = domain;
            env.rawMessage = objectMapper.writeValueAsString(payload);

            redisson.getTopic(RELAY_TOPIC).publish(objectMapper.writeValueAsString(env));
        } catch (Exception e) {
            log.error("[Signaling] Failed to publish unblock request for {}", studentId, e);
        }
    }

    
    public void publishProctorAlert(String studentId, Integer year, String domain,
                                    String alertType, String message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "proctor_alert");
            payload.put("alertType", alertType);
            payload.put("message", message);
            payload.put("studentId", studentId);
            payload.put("username", studentId);
            payload.put("year", year);
            payload.put("domain", domain);

            RelayEnvelope env = new RelayEnvelope();
            env.originInstanceId = instanceId;
            env.type = "proctor_alert";
            env.payloadRole = "student";
            env.senderRole = "student";
            env.senderUsername = studentId;
            env.senderYear = String.valueOf(year);
            env.senderDomain = domain;
            env.rawMessage = objectMapper.writeValueAsString(payload);

            redisson.getTopic(RELAY_TOPIC).publish(objectMapper.writeValueAsString(env));
        } catch (Exception e) {
            log.error("[Signaling] Failed to publish proctor alert for {}", studentId, e);
        }
    }

    
    private boolean mayProctor(WebSocketSession adminSession, String studentUsername) {
        if (Boolean.TRUE.equals(adminSession.getAttributes().get("superAdmin"))) {
            return true;
        }
        if (studentUsername == null) return false;

        String holder = assignmentService.proctorFor(studentUsername);
        return holder != null && holder.equals(adminSession.getAttributes().get("username"));
    }

    private void fanOutLocally(RelayEnvelope env) {
        TextMessage message = new TextMessage(env.rawMessage);

        for (WebSocketSession s : sessions.values()) {
            if (!s.isOpen()) continue;
            if (instanceId.equals(env.originInstanceId) && s.getId().equals(env.originSessionId)) continue;

            String sRole = (String) s.getAttributes().get("role");
            String sUsername = (String) s.getAttributes().get("username");
            String sStudentId = (String) s.getAttributes().get("studentId");
            String sYear = (String) s.getAttributes().get("year");
            String sDomain = (String) s.getAttributes().get("domain");
            String sFilterYear = (String) s.getAttributes().get("filterYear");
            String sFilterDomain = (String) s.getAttributes().get("filterDomain");

            try {
                if ("snapshot".equals(env.type)) {
                    if ("admin".equals(sRole)
                            && mayProctor(s, env.senderUsername)
                            && env.senderYear != null && env.senderYear.equals(sFilterYear)
                            && env.senderDomain != null && env.senderDomain.equals(sFilterDomain)) {
                        s.sendMessage(message);
                    }
                } else if (env.target != null) {
                    
                    
                    
                    
                    
                    
                    
                    boolean allowed = false;
                    boolean targetMatches = env.target.equals(sUsername) || env.target.equals(sStudentId);
                    if (targetMatches && "student".equals(env.senderRole) && "admin".equals(sRole)) {
                        allowed = mayProctor(s, env.senderUsername);
                    } else if (targetMatches && "admin".equals(env.senderRole) && "student".equals(sRole)) {
                        String holder = assignmentService.proctorFor(env.target);
                        allowed = env.senderSuperAdmin || (holder != null && holder.equals(env.senderUsername));
                    }
                    if (allowed) {
                        s.sendMessage(message);
                    }
                } else if ("admin_ready".equals(env.type) || "join".equals(env.type) && "admin".equals(env.payloadRole)) {
                    if ("student".equals(sRole)
                            && env.senderFilterYear != null && env.senderFilterYear.equals(sYear)
                            && env.senderFilterDomain != null && env.senderFilterDomain.equals(sDomain)) {
                        s.sendMessage(message);
                    }
                } else if ("student".equals(env.senderRole)) {
                    if ("admin".equals(sRole)
                            && mayProctor(s, env.senderUsername)
                            && env.senderYear != null && env.senderYear.equals(sFilterYear)
                            && env.senderDomain != null && env.senderDomain.equals(sFilterDomain)) {
                        s.sendMessage(message);
                    }
                } else {
                    s.sendMessage(message);
                }
            } catch (IOException e) {
                log.error("Failed to send message", e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        log.info("WebSocket connection closed: {}", session.getId());
    }

    public static class RelayEnvelope {
        public String originInstanceId;
        public String originSessionId;
        public String type;
        public String target;
        public String payloadRole;
        public String senderRole;
        public String senderUsername;
        public boolean senderSuperAdmin;
        public String senderYear;
        public String senderDomain;
        public String senderFilterYear;
        public String senderFilterDomain;
        public String rawMessage;
    }
}
