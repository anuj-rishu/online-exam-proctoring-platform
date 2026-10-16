package in.srminsider.labs.student;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.repository.TestSessionRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class SessionCacheService {

    private static final String KEY_PREFIX = "session:";
    private static final String DIRTY_SET_KEY = "session:dirty";
    private static final String SNAPSHOT_SEEN_PREFIX = "snapshot:seen:";
    private static final Duration SESSION_TTL = Duration.ofHours(6);
    private static final Duration SNAPSHOT_FRESHNESS = Duration.ofSeconds(90);

    private final TestSessionRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public static final Map<String, Long> SECTION_DURATIONS = Map.of(
        "Programming MCQ", 30L * 60L,
        "Aptitude MCQ", 5L * 60L,
        "Academics", 5L * 60L,
        "Programming Question", 40L * 60L
    );

    public SessionCacheService(TestSessionRepository repository, StringRedisTemplate redis) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = new ObjectMapper();
    }

    private String cacheKey(String username, Integer year, String domain) {
        return KEY_PREFIX + username + ":" + year + ":" + domain;
    }

    private String cacheKey(TestSession session) {
        return cacheKey(session.getUsername(), session.getYear(), session.getDomain());
    }

    private TestSession readFromRedis(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, TestSession.class);
        } catch (Exception e) {
            log.error("[Cache] Failed to deserialize session for key={}", key, e);
            return null;
        }
    }

    private void writeToRedis(String key, TestSession session) {
        try {
            String json = objectMapper.writeValueAsString(session);
            redis.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            log.error("[Cache] Failed to serialize session for key={}", key, e);
        }
    }

    public void recordSnapshot(String username) {
        if (username == null) return;
        redis.opsForValue().set(SNAPSHOT_SEEN_PREFIX + username,
                String.valueOf(System.currentTimeMillis()), SNAPSHOT_FRESHNESS);
    }

    public boolean hasRecentSnapshot(String username) {
        if (username == null) return false;
        return Boolean.TRUE.equals(redis.hasKey(SNAPSHOT_SEEN_PREFIX + username));
    }

    public Optional<TestSession> getSession(String username, Integer year, String domain) {
        String key = cacheKey(username, year, domain);

        TestSession cached = readFromRedis(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<TestSession> fromDb = repository.findByUsernameAndYearAndDomain(username, year, domain);
        fromDb.ifPresent(session -> writeToRedis(key, session));
        return fromDb;
    }

    public TestSession saveToCache(TestSession session) {
        String key = cacheKey(session);
        session.setLastUpdatedAt(System.currentTimeMillis());
        writeToRedis(key, session);
        redis.opsForSet().add(DIRTY_SET_KEY, key);
        return session;
    }

    public TestSession saveImmediate(TestSession session) {
        String key = cacheKey(session);
        session.setLastUpdatedAt(System.currentTimeMillis());
        TestSession saved = repository.save(session);
        writeToRedis(key, saved);
        redis.opsForSet().remove(DIRTY_SET_KEY, key);
        return saved;
    }

    
    public void evict(String username, Integer year, String domain) {
        String key = cacheKey(username, year, domain);
        redis.opsForSet().remove(DIRTY_SET_KEY, key);
        redis.delete(key);
    }

    @Scheduled(fixedRate = 10000)
    public void flushDirtySessions() {
        Set<String> dirtyKeys = redis.opsForSet().members(DIRTY_SET_KEY);
        if (dirtyKeys == null || dirtyKeys.isEmpty()) return;

        List<TestSession> batch = new ArrayList<>();
        List<String> processedKeys = new ArrayList<>();
        for (String key : dirtyKeys) {
            TestSession session = readFromRedis(key);
            processedKeys.add(key);
            if (session != null) {
                batch.add(session);
            }
        }

        if (!batch.isEmpty()) {
            try {
                repository.saveAll(batch);
                log.info("[Cache] Flushed {} dirty sessions to MongoDB", batch.size());
            } catch (Exception e) {
                log.error("[Cache] Failed to flush sessions, leaving dirty for retry", e);
                return;
            }
        }

        if (!processedKeys.isEmpty()) {
            redis.opsForSet().remove(DIRTY_SET_KEY, processedKeys.toArray());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Cache] Server shutting down — flushing dirty sessions...");
        try {
            flushDirtySessions();
        } catch (Exception e) {
            log.error("[Cache] CRITICAL: Failed to flush sessions on shutdown!", e);
        }
    }

    public boolean isTimeOver(TestSession session) {
        if (session == null || session.isSubmitted()) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (session.getLockedSections() != null &&
            !session.getLockedSections().isEmpty() &&
            session.getLockedSections().containsAll(SECTION_DURATIONS.keySet())) {
            return true;
        }

        long sessionAgeMillis = now - session.getCreatedAt();
        if (sessionAgeMillis >= 90L * 60L * 1000L) {
            return true;
        }

        if (session.getSectionStartTimes() != null && !session.getSectionStartTimes().isEmpty()) {
            boolean allStartedExpired = true;
            for (Map.Entry<String, Long> entry : session.getSectionStartTimes().entrySet()) {
                String section = entry.getKey();
                long startTime = entry.getValue();
                long totalSeconds = SECTION_DURATIONS.getOrDefault(section, 30L * 60L);
                long elapsed = (now - startTime) / 1000L;
                if (elapsed < totalSeconds && (session.getLockedSections() == null || !session.getLockedSections().contains(section))) {
                    allStartedExpired = false;
                    break;
                }
            }
            if (allStartedExpired && sessionAgeMillis >= 80L * 60L * 1000L) {
                return true;
            }
        }

        return false;
    }

    public boolean checkAndAutoSubmit(TestSession session) {
        if (session == null || session.isSubmitted()) {
            return false;
        }

        if (isTimeOver(session)) {
            performSubmit(session, true);
            return true;
        }

        return false;
    }

    public TestSession performSubmit(TestSession session, boolean auto) {
        long now = System.currentTimeMillis();
        session.setSubmitted(true);
        session.setAutoSubmitted(auto);
        if (session.getSubmittedAt() <= 0) {
            session.setSubmittedAt(now);
        }
        session.setLastUpdatedAt(now);

        if (session.getLockedSections() == null) {
            session.setLockedSections(new ArrayList<>());
        }
        for (String sec : SECTION_DURATIONS.keySet()) {
            if (!session.getLockedSections().contains(sec)) {
                session.getLockedSections().add(sec);
            }
        }

        if (session.getSectionStartTimes() != null) {
            if (session.getSectionEndTimes() == null) {
                session.setSectionEndTimes(new HashMap<>());
            }
            for (Map.Entry<String, Long> entry : session.getSectionStartTimes().entrySet()) {
                String section = entry.getKey();
                long startTime = entry.getValue();
                long totalSeconds = SECTION_DURATIONS.getOrDefault(section, 30L * 60L);
                long maxEndTime = startTime + (totalSeconds * 1000L);
                long currentEndTime = session.getSectionEndTimes().getOrDefault(section, maxEndTime);
                long finalEndTime = Math.min(currentEndTime, maxEndTime);
                session.getSectionEndTimes().put(section, finalEndTime);
            }
        }

        TestSession saved = saveImmediate(session);
        log.info("[Session] {} session for user={}, year={}, domain={}",
                auto ? "Auto-submitted expired" : "Manually submitted",
                session.getUsername(), session.getYear(), session.getDomain());
        return saved;
    }

    public Optional<TestSession> forceSubmit(String username, Integer year, String domain) {
        Optional<TestSession> opt;
        if (year != null && domain != null) {
            opt = getSession(username, year, domain);
        } else {
            List<TestSession> list = repository.findByUsername(username);
            opt = list.stream().max(Comparator.comparingLong(TestSession::getCreatedAt));
        }

        if (opt.isPresent()) {
            TestSession session = opt.get();
            return Optional.of(performSubmit(session, false));
        }
        return Optional.empty();
    }

    @Scheduled(fixedRate = 60000)
    public void autoSubmitExpiredSessions() {
        try {
            List<TestSession> unsubmitted = repository.findBySubmittedFalse();
            int count = 0;
            for (TestSession session : unsubmitted) {
                if (isTimeOver(session)) {
                    performSubmit(session, true);
                    count++;
                }
            }
            if (count > 0) {
                log.info("[AutoSubmit] Successfully auto-submitted {} expired sessions", count);
            }
        } catch (Exception e) {
            log.error("[AutoSubmit] Error during scheduled auto-submit scan", e);
        }
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        Long dirtyCount = redis.opsForSet().size(DIRTY_SET_KEY);
        stats.put("dirtySessions", dirtyCount == null ? 0 : dirtyCount);
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        stats.put("cachedSessions", keys == null ? 0 : keys.size());
        return stats;
    }
}
