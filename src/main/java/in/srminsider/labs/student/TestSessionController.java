package in.srminsider.labs.student;

import in.srminsider.labs.domain.model.TestCase;
import in.srminsider.labs.domain.model.AnomalyLog;
import in.srminsider.labs.domain.model.Problem;
import in.srminsider.labs.domain.model.ProblemCatalog;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.repository.ProblemRepository;
import in.srminsider.labs.admin.ProctorAssignmentService;
import in.srminsider.labs.proctoring.SignalingHandler;
import in.srminsider.labs.student.dto.SessionRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@Validated
@RequestMapping("/api/student/session")
@RequiredArgsConstructor
public class TestSessionController {

    private final SessionCacheService sessionCache;
    private final ProblemRepository problemRepository;
    private final SignalingHandler signalingHandler;
    private final ProctorAssignmentService proctorAssignmentService;
    private final in.srminsider.labs.domain.repository.UserRepository userRepository;

    // Physical screen.width, not viewport; touchPrimary is what rules out phones/tablets.
    public static final int MIN_DESKTOP_WIDTH = 800;

    /** Share of a sampled window carrying speech before it counts as talking. */
    private static final double SPEECH_RATIO_THRESHOLD = 0.35;

    /** Consecutive talking samples before it is recorded as a violation. */
    private static final int VOICE_STREAK_THRESHOLD = 2;

    
    private static List<String> orderedSections(TestSession session) {
        List<String> order = session.getSectionOrder();
        if (order == null || order.isEmpty()) {
            return ProblemCatalog.SECTIONS;
        }
        return order;
    }

    private static String firstUnfinishedSectionBefore(TestSession session, String section) {
        List<String> order = orderedSections(session);
        int index = order.indexOf(section);
        if (index < 0) return null;
        for (int i = 0; i < index; i++) {
            String earlier = order.get(i);
            if (!session.getLockedSections().contains(earlier)) {
                return earlier;
            }
        }
        return null;
    }

    private static boolean isSectionAccessible(TestSession session, String section) {
        if (session.getLockedSections().contains(section)) {
            return false;
        }
        if (!orderedSections(session).contains(section)) {
            return false;
        }
        return firstUnfinishedSectionBefore(session, section) == null;
    }

    private static String sectionBlockedMessage(TestSession session, String section) {
        if (session.getLockedSections().contains(section)) {
            return "This section is closed and can no longer be edited.";
        }
        String blocker = firstUnfinishedSectionBefore(session, section);
        if (blocker != null) {
            return "Finish and submit \"" + blocker + "\" before working on \"" + section + "\".";
        }
        return "\"" + section + "\" is not part of this test.";
    }

    private static final Map<String, Long> SECTION_DURATIONS = Map.of(
        "Programming MCQ", 30L * 60L,
        "Aptitude MCQ", 5L * 60L,
        "Academics", 5L * 60L,
        "Programming Question", 40L * 60L
    );

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping
    public ResponseEntity<?> getOrCreateSession(
            @RequestParam @Min(value = 1, message = "Year must be between 1 and 4")
                          @Max(value = 4, message = "Year must be between 1 and 4") Integer year,
            @RequestParam @NotBlank(message = "Domain is required")
                          @Size(max = 60, message = "Domain is too long") String domain) {

        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        // Enrolment is authoritative; null year/domain skipped so incomplete records don't lock out.
        var enrolled = userRepository.findByUsername(username);
        if (enrolled.isPresent()) {
            Integer enrolledYear = enrolled.get().getYear();
            String enrolledDomain = enrolled.get().getDomain();

            if (enrolledYear != null && !enrolledYear.equals(year)) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "You are enrolled in Year " + enrolledYear
                                + ". Go back and start the test from your dashboard."));
            }
            if (enrolledDomain != null && !enrolledDomain.equalsIgnoreCase(domain)) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "You are enrolled in " + enrolledDomain
                                + ", not " + domain
                                + ". Go back and start the test from your dashboard."));
            }
        }

        Optional<TestSession> existingSession = sessionCache.getSession(username, year, domain);

        TestSession session;
        List<Problem> problems;

        if (existingSession.isPresent()) {
            session = existingSession.get();

            if (session.isSubmitted() || sessionCache.isTimeOver(session)) {
                if (!session.isSubmitted()) {
                    sessionCache.performSubmit(session, true);
                }
                Map<String, Object> result = new HashMap<>();
                result.put("submitted", true);
                result.put("message", "This test has already been submitted.");
                return ResponseEntity.ok(result);
            }

            List<Problem> allProblems = problemRepository.findAllById(session.getProblemIds());
            Map<String, Problem> problemMap = allProblems.stream()
                    .collect(Collectors.toMap(Problem::getId, p -> p));

            problems = session.getProblemIds().stream()
                    .map(problemMap::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // A session opened before this track had any questions stored an
            // empty list and would keep serving it forever. Nothing has been
            // answered yet, so rebuilding it costs the student nothing.
            if (problems.isEmpty()) {
                problems = buildQuizSet(year, domain);
                if (!problems.isEmpty()) {
                    List<String> rebuiltIds = problems.stream().map(Problem::getId).collect(Collectors.toList());
                    Map<String, String> rebuiltStatuses = new HashMap<>();
                    for (String pid : rebuiltIds) {
                        rebuiltStatuses.put(pid, "not_visited");
                    }
                    rebuiltStatuses.put(rebuiltIds.get(0), "skipped");

                    session.setProblemIds(rebuiltIds);
                    session.setQuestionStatuses(rebuiltStatuses);
                    session.setSectionOrder(problems.stream()
                            .map(Problem::getSection).filter(Objects::nonNull).distinct()
                            .collect(Collectors.toList()));
                    session.setActiveSection(problems.get(0).getSection());
                    session.setCurrentIndex(0);
                    sessionCache.saveImmediate(session);
                    log.warn("[Session] Rebuilt empty question set for {} (year {}, {})", username, year, domain);
                }
            }

            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Map.Entry<String, Long> entry : session.getSectionStartTimes().entrySet()) {
                String section = entry.getKey();
                long startTime = entry.getValue();
                long totalSeconds = SECTION_DURATIONS.getOrDefault(section, 30L * 60L);
                long elapsedSeconds = (now - startTime) / 1000;
                if (elapsedSeconds >= totalSeconds && !session.getLockedSections().contains(section)) {
                    session.getLockedSections().add(section);
                    session.getSectionEndTimes().putIfAbsent(section, startTime + (totalSeconds * 1000));
                    changed = true;
                }
            }
            if (changed) {
                sessionCache.saveToCache(session);
            }

        } else {
            problems = buildQuizSet(year, domain);

            List<String> problemIds = problems.stream()
                    .map(Problem::getId)
                    .collect(Collectors.toList());

            Map<String, String> statuses = new HashMap<>();
            for (String pid : problemIds) {
                statuses.put(pid, "not_visited");
            }
            if (!problemIds.isEmpty()) {
                statuses.put(problemIds.get(0), "skipped");
            }

            List<String> sectionOrder = problems.stream()
                    .map(Problem::getSection)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            long now = System.currentTimeMillis();
            session = TestSession.builder()
                    .username(username)
                    .year(year)
                    .domain(domain)
                    .problemIds(problemIds)
                    .sectionOrder(sectionOrder)
                    .answers(new HashMap<>())
                    .questionStatuses(statuses)
                    .sectionStartTimes(new HashMap<>())
                    .lockedSections(new ArrayList<>())
                    .currentIndex(0)
                    .activeSection(problems.isEmpty() ? null : problems.get(0).getSection())
                    .tabSwitchCount(0)
                    .createdAt(now)
                    .lastUpdatedAt(now)
                    .submitted(false)
                    .build();

            session = sessionCache.saveImmediate(session);
        }

        List<Map<String, Object>> problemStubs = problems.stream().map(p -> {
            Map<String, Object> stub = new HashMap<>();
            stub.put("_uid", p.getId());
            stub.put("id", p.getId());
            stub.put("section", p.getSection());
            stub.put("type", p.getType());
            return stub;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", session.getId());
        response.put("problems", problemStubs);
        response.put("answers", session.getAnswers());
        response.put("questionStatuses", session.getQuestionStatuses());
        response.put("currentIndex", session.getCurrentIndex());
        response.put("activeSection", session.getActiveSection());
        response.put("lockedSections", session.getLockedSections());
        response.put("sectionOrder", orderedSections(session));
        response.put("tabSwitchCount", session.getTabSwitchCount());
        response.put("submitted", session.isSubmitted());
        response.put("isBlocked", session.isBlocked());
        response.put("anomalies", session.getAnomalies());

        Map<String, Object> sectionTimerState = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : session.getSectionStartTimes().entrySet()) {
            String section = entry.getKey();
            long startTime = entry.getValue();
            long totalSeconds = SECTION_DURATIONS.getOrDefault(section, 30L * 60L);
            long elapsedSeconds = (now - startTime) / 1000;
            long remainingSeconds = Math.max(0, totalSeconds - elapsedSeconds);

            Map<String, Object> timerInfo = new HashMap<>();
            timerInfo.put("totalSeconds", totalSeconds);
            timerInfo.put("remainingSeconds", remainingSeconds);
            timerInfo.put("isLocked", session.getLockedSections().contains(section));
            sectionTimerState.put(section, timerInfo);
        }
        response.put("sectionTimerState", sectionTimerState);

        return ResponseEntity.ok(response);
    }

    // Read-only; the session endpoint above creates a session as a side effect.
    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/status")
    public ResponseEntity<?> getSessionStatus(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String domain) {

        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Integer resolvedYear = year;
        String resolvedDomain = domain;
        var student = userRepository.findByUsername(username);
        if (student.isPresent()) {
            if (student.get().getYear() != null) resolvedYear = student.get().getYear();
            if (student.get().getDomain() != null) resolvedDomain = student.get().getDomain();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("year", resolvedYear);
        body.put("domain", resolvedDomain);

        if (resolvedYear == null || resolvedDomain == null) {
            body.put("exists", false);
            body.put("submitted", false);
            return ResponseEntity.ok(body);
        }

        Optional<TestSession> opt = sessionCache.getSession(username, resolvedYear, resolvedDomain);
        if (opt.isEmpty()) {
            body.put("exists", false);
            body.put("submitted", false);
            return ResponseEntity.ok(body);
        }

        TestSession session = opt.get();
        // Time-up counts as submitted for display; the scheduled sweep persists it.
        body.put("exists", true);
        body.put("submitted", session.isSubmitted() || sessionCache.isTimeOver(session));
        body.put("blocked", session.isBlocked());
        body.put("submittedAt", session.getSubmittedAt());
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/system-check")
    public ResponseEntity<?> systemCheck(@Valid @RequestBody SessionRequests.SystemCheck request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Session not found"));

        TestSession session = opt.get();

        if (!request.cameraLive() || !request.micLive() || !request.fullscreen() || request.monitors() > 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "Your device did not pass the hardware checks."));
        }

        // The exam is desktop-only. The page also blocks small screens on its
        // own, but that check lives in JavaScript a student can edit — this is
        // what actually withholds the questions.
        if (request.screenWidth() != null && request.screenWidth() > 0
                && request.screenWidth() < MIN_DESKTOP_WIDTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This test must be taken on a desktop or laptop. Your screen is too small."));
        }
        if (Boolean.TRUE.equals(request.touchPrimary())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This test must be taken on a desktop or laptop, not a phone or tablet."));
        }

        if (!sessionCache.hasRecentSnapshot(username)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No live webcam feed received. Allow camera access and wait a few seconds before continuing."));
        }

        session.setHardwareValidated(true);
        sessionCache.saveImmediate(session);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/problem/{id}")
    public ResponseEntity<?> getProblemDetails(
            @PathVariable @Size(max = 64, message = "Question reference is invalid") String id,
            @RequestParam @Min(value = 1, message = "Year must be between 1 and 4")
                          @Max(value = 4, message = "Year must be between 1 and 4") Integer year,
            @RequestParam @NotBlank(message = "Domain is required")
                          @Size(max = 60, message = "Domain is too long") String domain) {

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Optional<TestSession> existingSession = sessionCache.getSession(username, year, domain);
        if (existingSession.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active session found"));
        }

        TestSession session = existingSession.get();
        if (session.isBlocked()) {
            return ResponseEntity.status(403).body(Map.of("error", "Test is locked due to strict violations."));
        }
        if (!session.isHardwareValidated()) {
            return ResponseEntity.status(403).body(Map.of("error", "Hardware validation is pending."));
        }
        if (!session.getProblemIds().contains(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "Question not in your session"));
        }

        Optional<Problem> pOpt = problemRepository.findById(id);
        if (pOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Problem p = pOpt.get();

        Map<String, Object> response = new HashMap<>();
        response.put("id", p.getId());
        response.put("_uid", p.getId());
        response.put("title", p.getTitle());
        response.put("description", p.getDescription());
        response.put("section", p.getSection());
        response.put("type", p.getType());
        response.put("optionA", p.getOptionA());
        response.put("optionB", p.getOptionB());
        response.put("optionC", p.getOptionC());
        response.put("optionD", p.getOptionD());
        response.put("marks", p.getMarks() != null ? p.getMarks() : ("TEXT".equals(p.getType()) ? 5 : 1));

        if ("TEXT".equals(p.getType())) {
            response.put("inputFormat", p.getInputFormat());
            response.put("outputFormat", p.getOutputFormat());
            response.put("constraints", p.getConstraints());
            response.put("sampleCode", p.getSampleCode());

            if (p.getTestCases() != null) {
                List<Map<String, Object>> sampleTestCases = p.getTestCases().stream()
                        .filter(TestCase::isSample)
                        .map(tc -> {
                            Map<String, Object> tcMap = new HashMap<>();
                            tcMap.put("input", tc.getInput());
                            tcMap.put("expectedOutput", tc.getExpectedOutput());
                            tcMap.put("explanation", tc.getExplanation());
                            return tcMap;
                        })
                        .collect(java.util.stream.Collectors.toList());
                response.put("sampleTestCases", sampleTestCases);
                response.put("totalTestCases", p.getTestCases().size());
            }
        }

        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/answer")
    public ResponseEntity<?> saveAnswer(@Valid @RequestBody SessionRequests.SaveAnswer request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active session found"));
        }

        TestSession session = opt.get();
        if (session.isBlocked()) {
            return ResponseEntity.status(403).body(Map.of("error", "Test is locked due to strict violations."));
        }
        if (session.isSubmitted()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Test already submitted"));
        }

        String problemId = request.problemId();

        if (problemId != null && !problemId.isBlank()) {
            if (!session.getProblemIds().contains(problemId)) {
                return ResponseEntity.status(403).body(Map.of("error", "Question not in your session"));
            }

            String section = problemRepository.findById(problemId)
                    .map(Problem::getSection)
                    .orElse(null);

            if (section != null && !isSectionAccessible(session, section)) {
                return ResponseEntity.status(403).body(Map.of("error", sectionBlockedMessage(session, section)));
            }

            if (request.answer() != null) {
                session.getAnswers().put(problemId, request.answer());
            }
            if (request.status() != null) {
                session.getQuestionStatuses().put(problemId, request.status());
            }
        }

        if (request.currentIndex() != null) {
            session.setCurrentIndex(request.currentIndex());
        }
        if (request.activeSection() != null && isSectionAccessible(session, request.activeSection())) {
            session.setActiveSection(request.activeSection());
        }

        sessionCache.saveToCache(session);

        return ResponseEntity.ok(Map.of("saved", true, "tabSwitchCount", session.getTabSwitchCount()));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/submit")
    public ResponseEntity<?> submitTest(@Valid @RequestBody SessionRequests.SubmitTest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active session found"));
        }

        TestSession session = opt.get();
        if (session.isBlocked()) {
            return ResponseEntity.status(403).body(Map.of("error", "Test is locked due to strict violations."));
        }

        sessionCache.performSubmit(session, false);
        return ResponseEntity.ok(Map.of("submitted", true));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/start-section")
    public ResponseEntity<?> startSection(@Valid @RequestBody SessionRequests.SectionAction request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        String section = request.section();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active session found"));
        }

        TestSession session = opt.get();
        if (session.isBlocked()) {
            return ResponseEntity.status(403).body(Map.of("error", "Test is locked due to strict violations."));
        }
        if (!SECTION_DURATIONS.containsKey(section)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown section"));
        }
        if (!isSectionAccessible(session, section)) {
            return ResponseEntity.status(403).body(Map.of("error", sectionBlockedMessage(session, section)));
        }
        long now = System.currentTimeMillis();
        long totalSeconds = SECTION_DURATIONS.getOrDefault(section, 30L * 60L);

        session.getSectionStartTimes().putIfAbsent(section, now);

        long startTime = session.getSectionStartTimes().get(section);
        long elapsedSeconds = (now - startTime) / 1000;
        long remainingSeconds = Math.max(0, totalSeconds - elapsedSeconds);

        boolean isLocked = session.getLockedSections().contains(section);
        if (remainingSeconds <= 0 && !isLocked) {
            session.getLockedSections().add(section);
            session.getSectionEndTimes().putIfAbsent(section, startTime + (totalSeconds * 1000));
            isLocked = true;
        }

        sessionCache.saveToCache(session);

        Map<String, Object> response = new HashMap<>();
        response.put("section", section);
        response.put("totalSeconds", totalSeconds);
        response.put("remainingSeconds", remainingSeconds);
        response.put("isLocked", isLocked);
        response.put("lockedSections", session.getLockedSections());

        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/anomaly")
    public ResponseEntity<?> logAnomaly(@Valid @RequestBody SessionRequests.LogAnomaly request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        String type = request.type();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) return ResponseEntity.badRequest().build();

        TestSession session = opt.get();

        AnomalyLog log = AnomalyLog.builder()
            .timestamp(System.currentTimeMillis())
            .type(type)
            .description(request.description())
            .build();

        session.getAnomalies().add(log);

        if ("TAB_SWITCH".equals(type)) {
            session.setTabSwitchCount(session.getTabSwitchCount() + 1);
            if (session.getTabSwitchCount() > 3) {
                session.setBlocked(true);
            }
        }

        if ("SCREENSHOT".equals(type)) {
            session.setScreenshotCount(session.getScreenshotCount() + 1);
        }

        if ("APP_SWITCH".equals(type)) {
            session.setAppSwitchCount(session.getAppSwitchCount() + 1);
        }

        sessionCache.saveToCache(session);

        if ("SCREENSHOT".equals(type)) {
            signalingHandler.publishProctorAlert(username, request.year(), request.domain(),
                    "SCREENSHOT", "Screenshot attempt detected (" + session.getScreenshotCount() + " so far).");
        }

        if ("APP_SWITCH".equals(type)) {
            signalingHandler.publishProctorAlert(username, request.year(), request.domain(),
                    "APP_SWITCH", "Switched to another application (" + session.getAppSwitchCount() + " so far).");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("isBlocked", session.isBlocked());
        response.put("tabSwitchCount", session.getTabSwitchCount());
        response.put("screenshotCount", session.getScreenshotCount());
        response.put("appSwitchCount", session.getAppSwitchCount());

        return ResponseEntity.ok(response);
    }

    /**
     * Sustained talking during an exam is a proctoring signal regardless of
     * whose voice it is — the student should be working silently and alone.
     *
     * The browser measures speech-band energy from the microphone it already
     * has open and reports a summary; the thresholds and the violation record
     * stay here on the server. This deliberately does not claim to identify
     * *who* is speaking: that would need voice enrolment and a speaker model,
     * so the recorded anomaly says what was actually observed.
     */
    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/voice-check")
    public ResponseEntity<?> voiceCheck(@Valid @RequestBody SessionRequests.VoiceCheck request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) return ResponseEntity.ok(Map.of("warning", false));

        TestSession session = opt.get();
        boolean speechPresent = request.speechRatio() != null
                && request.speechRatio() >= SPEECH_RATIO_THRESHOLD;

        if (!speechPresent) {
            if (session.getConsecutiveVoiceHits() != 0) {
                session.setConsecutiveVoiceHits(0);
                sessionCache.saveToCache(session);
            }
            return ResponseEntity.ok(Map.of("warning", false));
        }

        int streak = session.getConsecutiveVoiceHits() + 1;
        if (streak < VOICE_STREAK_THRESHOLD) {
            session.setConsecutiveVoiceHits(streak);
            sessionCache.saveToCache(session);
            return ResponseEntity.ok(Map.of("warning", false));
        }

        session.setConsecutiveVoiceHits(0);
        session.setVoiceDetectionCount(session.getVoiceDetectionCount() + 1);

        String message = "Sustained talking picked up by the microphone during the exam.";
        session.getAnomalies().add(AnomalyLog.builder()
                .timestamp(System.currentTimeMillis())
                .type("VOICE_DETECTED")
                .description(message)
                .build());
        sessionCache.saveToCache(session);
        signalingHandler.publishProctorAlert(username, request.year(), request.domain(),
                "VOICE_DETECTED", message);

        return ResponseEntity.ok(Map.of(
                "warning", true,
                "type", "VOICE_DETECTED",
                "message", "Talking was detected and reported to your proctor.",
                "voiceDetectionCount", session.getVoiceDetectionCount()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/unblock")
    public ResponseEntity<?> unblockStudent(@Valid @RequestBody SessionRequests.UnblockStudent request) {
        String username = request.studentId();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active session found for this student."));
        }

        TestSession session = opt.get();
        if (session.isSubmitted()) {
            return ResponseEntity.badRequest().body(Map.of("error", "This student has already submitted the test."));
        }
        
        
        
        if (!session.isBlocked()) {
            return ResponseEntity.badRequest().body(Map.of("error", "This student is not currently blocked."));
        }

        
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isSuperAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        if (!isSuperAdmin) {
            String adminUsername = auth.getName();
            String assignedProctor = proctorAssignmentService.proctorFor(username);
            if (assignedProctor == null || !assignedProctor.equals(adminUsername)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "You are not the assigned proctor for this student."));
            }
        }

        session.setBlocked(false);
        session.setTabSwitchCount(0);
        // Left standing, the count is already at the block threshold, so the
        // next single mismatch re-blocks the student the proctor just cleared.
        session.setFaceMismatchCount(0);
        session.setConsecutiveFaceMismatches(0);

        AnomalyLog log = AnomalyLog.builder()
            .timestamp(System.currentTimeMillis())
            .type("ADMIN_UNBLOCK")
            .description("Proctor approved unblock request.")
            .build();
        session.getAnomalies().add(log);

        sessionCache.saveImmediate(session);

        return ResponseEntity.ok(Map.of("success", true));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/submit-section")
    public ResponseEntity<?> submitSection(@Valid @RequestBody SessionRequests.SectionAction request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        String section = request.section();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active session found"));
        }

        TestSession session = opt.get();
        if (session.isSubmitted()) {
            return ResponseEntity.badRequest().body(Map.of("error", "This test has already been submitted."));
        }
        if (session.isBlocked()) {
            return ResponseEntity.status(403).body(Map.of("error", "Test is locked due to strict violations."));
        }
        if (session.getLockedSections().contains(section)) {
            return ResponseEntity.badRequest().body(Map.of("error", "This section has already been submitted."));
        }
        if (!isSectionAccessible(session, section)) {
            return ResponseEntity.status(403).body(Map.of("error", sectionBlockedMessage(session, section)));
        }

        List<Problem> sectionProblems = problemRepository.findAllById(session.getProblemIds()).stream()
                .filter(p -> section.equals(p.getSection()))
                .collect(Collectors.toList());

        long unanswered = sectionProblems.stream()
                .filter(p -> {
                    String answer = session.getAnswers().get(p.getId());
                    return answer == null || answer.isBlank();
                })
                .count();

        if (unanswered > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Answer all " + unanswered + " remaining question(s) in \"" + section + "\" before submitting it.",
                    "unanswered", unanswered));
        }

        session.getLockedSections().add(section);
        session.getSectionEndTimes().putIfAbsent(section, System.currentTimeMillis());

        String nextSection = orderedSections(session).stream()
                .filter(s -> !session.getLockedSections().contains(s))
                .findFirst()
                .orElse(null);
        session.setActiveSection(nextSection);

        sessionCache.saveImmediate(session);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("lockedSections", session.getLockedSections());
        response.put("nextSection", nextSection);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/request-unblock")
    public ResponseEntity<?> requestUnblock(@Valid @RequestBody SessionRequests.SubmitTest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No active session found"));

        TestSession session = opt.get();
        if (!session.isBlocked()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Your test is not currently blocked."));
        }

        AnomalyLog log = AnomalyLog.builder()
            .timestamp(System.currentTimeMillis())
            .type("UNBLOCK_REQUESTED")
            .description("Student requested a proctor unblock.")
            .build();
        session.getAnomalies().add(log);
        sessionCache.saveToCache(session);

        signalingHandler.publishUnblockRequest(username, request.year(), request.domain());

        return ResponseEntity.ok(Map.of("success", true));
    }

    private List<Problem> buildQuizSet(Integer year, String domain) {
        List<Problem> allProblems = problemRepository.findByYearAndDomain(year, domain);

        Map<String, List<Problem>> grouped = allProblems.stream()
                .filter(p -> p.getSection() != null)
                .collect(Collectors.groupingBy(Problem::getSection));

        List<Problem> quiz = new ArrayList<>();

        List<Problem> progMcq = grouped.getOrDefault("Programming MCQ", new ArrayList<>());
        Collections.shuffle(progMcq);
        quiz.addAll(progMcq.stream().limit(30).collect(Collectors.toList()));

        List<Problem> aptMcq = grouped.getOrDefault("Aptitude MCQ", new ArrayList<>());
        Collections.shuffle(aptMcq);
        quiz.addAll(aptMcq.stream().limit(5).collect(Collectors.toList()));

        List<Problem> academics = grouped.getOrDefault("Academics", new ArrayList<>());
        Collections.shuffle(academics);
        quiz.addAll(academics.stream().limit(5).collect(Collectors.toList()));

        List<Problem> progQ = grouped.getOrDefault("Programming Question", new ArrayList<>());
        Collections.shuffle(progQ);
        quiz.addAll(progQ.stream().limit(10).collect(Collectors.toList()));

        return quiz;
    }
}
