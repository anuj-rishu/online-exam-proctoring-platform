package in.srminsider.labs.admin;

import in.srminsider.labs.domain.model.AnomalyLog;
import in.srminsider.labs.domain.model.Problem;
import in.srminsider.labs.domain.model.ProblemCatalog;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.ProblemRepository;
import in.srminsider.labs.domain.repository.TestSessionRepository;
import in.srminsider.labs.domain.repository.UserRepository;
import in.srminsider.labs.shared.CloudflareR2Service;
import in.srminsider.labs.student.SessionCacheService;
import com.opencsv.CSVWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin/submissions")
@RequiredArgsConstructor
public class AdminSubmissionController {

    private final TestSessionRepository testSessionRepository;
    private final ProblemRepository problemRepository;
    private final UserRepository userRepository;
    private final CloudflareR2Service r2Service;
    private final SessionCacheService sessionCache;
    private final CodeSimilarityService codeSimilarityService;
    private final ViolationReportPdfService violationReportPdfService;
    private final in.srminsider.labs.execution.CodeExecutionQueueService codeExecutionQueueService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Flags near-identical coding answers between students. Restricted to
     * super admins because it inherently compares across the whole cohort,
     * crossing the per-proctor student boundaries the rest of the app keeps.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/plagiarism")
    public ResponseEntity<?> getPlagiarismReport(
            @RequestParam Integer year,
            @RequestParam String domain,
            @RequestParam(defaultValue = "0.65") double threshold) {

        if (threshold < 0.0 || threshold > 1.0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Threshold must be between 0 and 1."));
        }

        List<CodeSimilarityService.SimilarityHit> hits =
                codeSimilarityService.findSuspiciousPairs(year, domain, threshold);

        List<Map<String, Object>> pairs = hits.stream()
                .map(hit -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("problemId", hit.problemId());
                    row.put("problemTitle", hit.problemTitle() != null ? hit.problemTitle() : "Untitled");
                    row.put("studentA", hit.studentA());
                    row.put("studentB", hit.studentB());
                    row.put("similarity", Math.round(hit.similarity() * 1000.0) / 10.0);
                    row.put("codeA", hit.codeA() != null ? hit.codeA() : "");
                    row.put("codeB", hit.codeB() != null ? hit.codeB() : "");
                    return row;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "pairs", pairs,
                "count", pairs.size(),
                "threshold", Math.round(threshold * 1000.0) / 10.0,
                "year", year,
                "domain", domain));
    }

    // Fixed band the programming section is scaled onto, so every track's paper totals the same.
    private static final int PROGRAMMING_SECTION_MARKS = 10;

    // Fixed marks each section is worth. Every paper totals the sum of these
    // regardless of how many questions it drew or what they were worth.
    private static final Map<String, Integer> SECTION_MARKS = new LinkedHashMap<>(Map.of(
            "Programming MCQ", 30,
            "Aptitude MCQ", 5,
            "Academics", 5,
            ProblemCatalog.PROGRAMMING_QUESTION, PROGRAMMING_SECTION_MARKS));

    // Marks a remark as machine-written so a re-run may replace it.
    private static final String AUTO_REMARK_PREFIX = "Auto: ";

    private static boolean contains(String value, String lowercaseNeedle) {
        return value != null && value.toLowerCase().contains(lowercaseNeedle);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ResponseEntity<?> getAllSubmissions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search) {

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);

        Map<String, User> userByUsername = userRepository.findAll().stream()
                .filter(u -> u.getUsername() != null)
                .collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));

        // Sessions store only a username, so email search resolves via user records.
        org.springframework.data.domain.Page<TestSession> sessionPage;
        if (search != null && !search.isBlank()) {
            String needle = search.trim().toLowerCase();
            List<String> matching = userByUsername.values().stream()
                    .filter(u -> contains(u.getEmail(), needle)
                            || contains(u.getUsername(), needle)
                            || contains(u.getName(), needle))
                    .map(User::getUsername)
                    .toList();

            sessionPage = matching.isEmpty()
                    ? org.springframework.data.domain.Page.empty(pageable)
                    : testSessionRepository.findByUsernameIn(matching, pageable);
        } else {
            sessionPage = testSessionRepository.findAll(pageable);
        }

        List<Problem> allProblems = problemRepository.findAll();

        Map<String, String> problemAnswers = allProblems.stream()
                .filter(p -> p.getCorrectOption() != null)
                .collect(Collectors.toMap(Problem::getId, Problem::getCorrectOption, (a, b) -> a));

        Map<String, Problem> problemMap = allProblems.stream()
                .collect(Collectors.toMap(Problem::getId, p -> p, (a, b) -> a));

        Map<String, Map<String, Object>> problemDetails = new HashMap<>();
        for (Problem p : allProblems) {
            Map<String, Object> pInfo = new HashMap<>();
            pInfo.put("id", p.getId());
            pInfo.put("title", p.getTitle() != null ? p.getTitle() : "");
            pInfo.put("text", p.getDescription() != null ? p.getDescription() : (p.getTitle() != null ? p.getTitle() : ""));
            pInfo.put("section", p.getSection() != null ? p.getSection() : "");
            pInfo.put("type", p.getType() != null ? p.getType() : "MCQ");
            pInfo.put("optionA", p.getOptionA() != null ? p.getOptionA() : "");
            pInfo.put("optionB", p.getOptionB() != null ? p.getOptionB() : "");
            pInfo.put("optionC", p.getOptionC() != null ? p.getOptionC() : "");
            pInfo.put("optionD", p.getOptionD() != null ? p.getOptionD() : "");
            pInfo.put("correctOption", p.getCorrectOption() != null ? p.getCorrectOption() : "");
            pInfo.put("marks", p.getMarks() != null ? p.getMarks() : ("TEXT".equals(p.getType()) ? 5 : 1));

            if ("TEXT".equals(p.getType())) {
                pInfo.put("inputFormat", p.getInputFormat() != null ? p.getInputFormat() : "");
                pInfo.put("outputFormat", p.getOutputFormat() != null ? p.getOutputFormat() : "");
                pInfo.put("constraints", p.getConstraints() != null ? p.getConstraints() : "");
                pInfo.put("totalTestCases", p.getTestCases() != null ? p.getTestCases().size() : 0);
            }
            problemDetails.put(p.getId(), pInfo);
        }

        List<Map<String, Object>> response = new ArrayList<>();

        for (TestSession session : sessionPage.getContent()) {
            if (!session.isSubmitted() && sessionCache.isTimeOver(session)) {
                sessionCache.checkAndAutoSubmit(session);
            }

            User rowUser = userByUsername.get(session.getUsername());

            Map<String, Object> sessionData = new HashMap<>();
            sessionData.put("username", session.getUsername());
            sessionData.put("email", rowUser != null && rowUser.getEmail() != null ? rowUser.getEmail() : "");
            sessionData.put("name", rowUser != null && rowUser.getName() != null ? rowUser.getName() : "");
            sessionData.put("year", session.getYear());
            sessionData.put("domain", session.getDomain());
            sessionData.put("submitted", session.isSubmitted());
            sessionData.put("autoSubmitted", session.isAutoSubmitted());
            sessionData.put("submittedAt", session.getSubmittedAt());
            sessionData.put("createdAt", session.getCreatedAt());

            Map<String, Object> sectionTimings = new HashMap<>();
            for (Map.Entry<String, Long> entry : session.getSectionStartTimes().entrySet()) {
                String section = entry.getKey();
                long startTime = entry.getValue();
                long totalSeconds = SessionCacheService.SECTION_DURATIONS.getOrDefault(section, 30L * 60L);

                long maxEndTime = startTime + (totalSeconds * 1000L);
                long endTime = session.getSectionEndTimes().getOrDefault(section, Math.min(System.currentTimeMillis(), maxEndTime));
                endTime = Math.min(endTime, maxEndTime);

                long timeTakenSec = Math.max(0, (endTime - startTime) / 1000L);
                timeTakenSec = Math.min(timeTakenSec, totalSeconds);
                sectionTimings.put(section, timeTakenSec);
            }

            Map<String, Object> metrics = computeSubmissionMetrics(session, problemAnswers, problemMap);

            sessionData.putAll(metrics);
            sessionData.put("sectionTimings", sectionTimings);
            sessionData.put("problemIds", session.getProblemIds());
            sessionData.put("answers", session.getAnswers());
            sessionData.put("manualMarks", session.getManualMarks());
            sessionData.put("evaluationRemarks", session.getEvaluationRemarks());
            sessionData.put("evaluated", session.isEvaluated());
            sessionData.put("anomalies", session.getAnomalies());
            sessionData.put("isBlocked", session.isBlocked());
            sessionData.put("tabSwitchCount", session.getTabSwitchCount());
            sessionData.put("screenshotCount", session.getScreenshotCount());
            sessionData.put("appSwitchCount", session.getAppSwitchCount());

            response.add(sessionData);
        }

        Map<String, Object> finalResponse = new HashMap<>();
        finalResponse.put("submissions", response);
        finalResponse.put("problems", problemDetails);
        finalResponse.put("totalPages", sessionPage.getTotalPages());
        finalResponse.put("totalElements", sessionPage.getTotalElements());
        finalResponse.put("currentPage", sessionPage.getNumber());
        finalResponse.put("last", sessionPage.isLast());

        return ResponseEntity.ok(finalResponse);
    }

    // Mirrors ViolationReportPdfService.TYPE_LABELS (private there); keep both in sync.
    private static final Map<String, String> VIOLATION_COLUMN_LABELS = new LinkedHashMap<>();
    static {
        VIOLATION_COLUMN_LABELS.put("TAB_SWITCH", "Tab switches");
        VIOLATION_COLUMN_LABELS.put("APP_SWITCH", "Switched to another application");
        VIOLATION_COLUMN_LABELS.put("COPY_PASTE", "Copy / paste attempts");
        VIOLATION_COLUMN_LABELS.put("SCREENSHOT", "Screenshot attempts");
        VIOLATION_COLUMN_LABELS.put("FACE_MISMATCH", "Different face detected");
        VIOLATION_COLUMN_LABELS.put("MULTIPLE_FACES", "Multiple faces in frame");
        VIOLATION_COLUMN_LABELS.put("LOOKING_AWAY", "Looking away from screen");
        VIOLATION_COLUMN_LABELS.put("VOICE_DETECTED", "Talking detected");
    }

    // One row per student: identity, marks, percentage, per-type violation counts.
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/export")
    public ResponseEntity<Resource> exportSubmissionsCsv(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String domain) {

        List<TestSession> sessions = (year != null && domain != null)
                ? testSessionRepository.findByYearAndDomain(year, domain)
                : testSessionRepository.findAll();

        List<Problem> allProblems = problemRepository.findAll();
        Map<String, String> problemAnswers = allProblems.stream()
                .filter(p -> p.getCorrectOption() != null)
                .collect(Collectors.toMap(Problem::getId, Problem::getCorrectOption, (a, b) -> a));
        Map<String, Problem> problemMap = allProblems.stream()
                .collect(Collectors.toMap(Problem::getId, p -> p, (a, b) -> a));

        Map<String, User> userByUsername = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));

        List<String> header = new ArrayList<>(List.of(
                "Name", "Email", "Username", "Year", "Domain",
                "Marks Obtained", "Marks Out Of", "Percentage"));
        header.addAll(VIOLATION_COLUMN_LABELS.values());
        header.add("Total Recorded Events");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.writeNext(header.toArray(new String[0]));

            for (TestSession session : sessions) {
                User user = userByUsername.get(session.getUsername());
                Map<String, Object> metrics = computeSubmissionMetrics(session, problemAnswers, problemMap);

                Map<String, Long> anomalyCounts = session.getAnomalies().stream()
                        .filter(a -> a.getType() != null)
                        .collect(Collectors.groupingBy(AnomalyLog::getType, Collectors.counting()));

                List<String> row = new ArrayList<>(List.of(
                        user != null && user.getName() != null ? user.getName() : session.getUsername(),
                        user != null && user.getEmail() != null ? user.getEmail() : "",
                        session.getUsername(),
                        String.valueOf(session.getYear()),
                        String.valueOf(session.getDomain()),
                        String.valueOf(metrics.get("totalScore")),
                        String.valueOf(metrics.get("totalMarksPossible")),
                        metrics.get("percentage") + "%"));

                for (String type : VIOLATION_COLUMN_LABELS.keySet()) {
                    row.add(String.valueOf(anomalyCounts.getOrDefault(type, 0L)));
                }
                row.add(String.valueOf(session.getAnomalies().size()));

                writer.writeNext(row.toArray(new String[0]));
            }
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }

        String filename = "submissions-export" + (year != null ? "-y" + year : "")
                + (domain != null ? "-" + domain.replaceAll("[^A-Za-z0-9]", "") : "") + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(new ByteArrayResource(out.toByteArray()));
    }

    /**
     * Grades the programming section by running each stored answer against the
     * question's test cases. Runs here rather than on submit: a full paper is
     * tens of sandbox executions, which would hold the student's submit request
     * open for a minute and pile up when a cohort finishes together.
     * A mark already entered by a human is never overwritten.
     */
    // A remark the admin typed is their judgement, so it is left alone.
    private void recordRemark(TestSession session, String problemId, String remark) {
        if (session.getEvaluationRemarks() == null) session.setEvaluationRemarks(new HashMap<>());
        String existing = session.getEvaluationRemarks().get(problemId);
        if (existing == null || existing.isBlank() || existing.startsWith(AUTO_REMARK_PREFIX)) {
            session.getEvaluationRemarks().put(problemId, AUTO_REMARK_PREFIX + remark);
        }
    }

    private static String testCasesSummary(Map<String, Object> result, String language) {
        Object passed = result.get("passedTestCases");
        Object total = result.get("totalTestCases");
        int p = passed instanceof Number n ? n.intValue() : 0;
        int tt = total instanceof Number n ? n.intValue() : 0;
        return p + "/" + tt + " test cases passed (" + language + ")";
    }

    private int autoScoreProgrammingAnswers(TestSession session, Map<String, Problem> problemMap, boolean regrade) {
        if (session.getProblemIds() == null || session.getAnswers() == null) return 0;
        if (session.getManualMarks() == null) session.setManualMarks(new HashMap<>());

        int graded = 0;
        for (String pid : session.getProblemIds()) {
            Problem prob = problemMap.get(pid);
            if (prob == null || !ProblemCatalog.PROGRAMMING_QUESTION.equals(prob.getSection())) continue;
            // A remark that isn't machine-written means a human graded this, and
            // their judgement stands even on a re-grade.
            String existingRemark = session.getEvaluationRemarks() == null
                    ? null : session.getEvaluationRemarks().get(pid);
            boolean humanGraded = existingRemark != null && !existingRemark.isBlank()
                    && !existingRemark.startsWith(AUTO_REMARK_PREFIX);
            if (humanGraded) continue;
            if (!regrade && session.getManualMarks().containsKey(pid)) continue;

            int maxMarks = prob.getMarks() != null ? prob.getMarks() : 5;
            String raw = session.getAnswers().get(pid);
            if (raw == null || raw.isBlank() || prob.getTestCases() == null || prob.getTestCases().isEmpty()) {
                session.getManualMarks().put(pid, 0);
                recordRemark(session, pid, "No answer submitted.");
                graded++;
                continue;
            }

            String language = "python";
            String code = null;
            try {
                var node = objectMapper.readTree(raw);
                if (node.hasNonNull("language")) language = node.get("language").asText();
                if (node.hasNonNull("code")) code = node.get("code").asText();
            } catch (Exception e) {
                code = raw; // older answers were stored as bare source
            }

            if (code == null || code.isBlank()) {
                session.getManualMarks().put(pid, 0);
                recordRemark(session, pid, "Editor was left empty.");
                graded++;
                continue;
            }

            try {
                Map<String, Object> result = codeExecutionQueueService.executeTestCases(
                        language, code, prob.getTestCases(), maxMarks);
                Object score = result.get("score");
                session.getManualMarks().put(pid, score instanceof Number n ? n.intValue() : 0);
                recordRemark(session, pid, testCasesSummary(result, language));
            } catch (Exception e) {
                log.error("[Evaluate] Auto-scoring failed for {} on {}", session.getUsername(), pid, e);
                session.getManualMarks().put(pid, 0);
                recordRemark(session, pid, "Could not be run — scored 0. Review manually.");
            }
            graded++;
        }
        return graded;
    }

    private Map<String, Object> computeSubmissionMetrics(TestSession session, Map<String, String> problemAnswers, Map<String, Problem> problemMap) {
        Map<String, Integer> earned = new HashMap<>();
        Map<String, Integer> available = new HashMap<>();

        int totalQuestions = 0;
        int attemptedCount = 0;
        int correctCount = 0;
        int incorrectCount = 0;
        int manualScore = 0;

        if (session.getProblemIds() != null) {
            totalQuestions = session.getProblemIds().size();
            for (String pid : session.getProblemIds()) {
                Problem prob = problemMap.get(pid);
                // A question deleted after the paper was built cannot be earned,
                // so it must not inflate the denominator either.
                if (prob == null) continue;

                String section = prob.getSection() != null ? prob.getSection() : "";
                if (!SECTION_MARKS.containsKey(section)) continue;

                int marks = prob.getMarks() != null ? prob.getMarks()
                        : ("TEXT".equals(prob.getType()) ? 5 : 1);
                available.merge(section, marks, Integer::sum);

                String answer = session.getAnswers() != null ? session.getAnswers().get(pid) : null;
                if (answer != null && !answer.isBlank()) attemptedCount++;

                Integer manual = session.getManualMarks() != null ? session.getManualMarks().get(pid) : null;
                if (manual != null) {
                    earned.merge(section, Math.min(manual, marks), Integer::sum);
                    manualScore += manual;
                    continue;
                }

                String correct = problemAnswers.get(pid);
                if (correct == null) continue;
                if (answer != null && correct.trim().equalsIgnoreCase(answer.trim())) {
                    earned.merge(section, marks, Integer::sum);
                    correctCount++;
                } else if (answer != null && !answer.isBlank()) {
                    incorrectCount++;
                }
            }
        }

        // Each section is scaled onto a fixed band, so a paper totals the same
        // however many questions it drew or whatever those questions were worth.
        double totalScore = 0.0;
        int totalMarksPossible = 0;
        Map<String, Object> sectionScores = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> band : SECTION_MARKS.entrySet()) {
            String section = band.getKey();
            int rawMax = available.getOrDefault(section, 0);
            if (rawMax <= 0) continue;

            int rawEarned = Math.min(earned.getOrDefault(section, 0), rawMax);
            double scaled = Math.round(((double) rawEarned / rawMax) * band.getValue() * 10.0) / 10.0;

            totalScore += scaled;
            totalMarksPossible += band.getValue();

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("score", scaled);
            detail.put("maxScore", band.getValue());
            detail.put("rawScore", rawEarned);
            detail.put("rawMax", rawMax);
            sectionScores.put(section, detail);
        }

        totalScore = Math.round(totalScore * 10.0) / 10.0;
        double percentage = totalMarksPossible > 0
                ? Math.round((totalScore / totalMarksPossible) * 1000.0) / 10.0
                : 0.0;

        int programmingRawMax = available.getOrDefault(ProblemCatalog.PROGRAMMING_QUESTION, 0);
        int programmingRawScore = earned.getOrDefault(ProblemCatalog.PROGRAMMING_QUESTION, 0);
        Object programmingBand = sectionScores.containsKey(ProblemCatalog.PROGRAMMING_QUESTION)
                ? ((Map<?, ?>) sectionScores.get(ProblemCatalog.PROGRAMMING_QUESTION)).get("score") : 0.0;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalQuestions", totalQuestions);
        metrics.put("totalMarksPossible", totalMarksPossible);
        metrics.put("attemptedCount", attemptedCount);
        metrics.put("correctCount", correctCount);
        metrics.put("incorrectCount", incorrectCount);
        metrics.put("unansweredCount", Math.max(0, totalQuestions - attemptedCount));
        metrics.put("manualScore", manualScore);
        metrics.put("sectionScores", sectionScores);
        metrics.put("programmingRawScore", programmingRawScore);
        metrics.put("programmingRawMax", programmingRawMax);
        metrics.put("programmingScore", programmingBand);
        metrics.put("programmingMaxScore", programmingRawMax > 0 ? PROGRAMMING_SECTION_MARKS : 0);
        metrics.put("totalScore", totalScore);
        metrics.put("percentage", percentage);
        return metrics;
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/{username}/evaluate")
    public ResponseEntity<?> evaluateSubmission(
            @PathVariable String username,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String domain,
            @RequestBody(required = false) Map<String, Object> body) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()))) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied. Only Super Admins can evaluate submissions."));
        }

        Optional<TestSession> opt;
        if (year != null && domain != null) {
            opt = sessionCache.getSession(username, year, domain);
        } else {
            List<TestSession> list = testSessionRepository.findByUsername(username);
            opt = list.stream().max(Comparator.comparingLong(TestSession::getCreatedAt));
        }

        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No test session found for student " + username));
        }

        TestSession session = opt.get();

        if (body != null && body.containsKey("manualMarks")) {
            Object rawMarks = body.get("manualMarks");
            if (rawMarks instanceof Map<?, ?> incomingMarks) {
                if (session.getManualMarks() == null) {
                    session.setManualMarks(new HashMap<>());
                }
                for (Map.Entry<?, ?> e : incomingMarks.entrySet()) {
                    if (e.getValue() != null) {
                        try {
                            session.getManualMarks().put(String.valueOf(e.getKey()), Integer.parseInt(e.getValue().toString()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }

        if (body != null && body.containsKey("remarks")) {
            Object rawRemarks = body.get("remarks");
            if (rawRemarks instanceof Map<?, ?> incomingRemarks) {
                if (session.getEvaluationRemarks() == null) {
                    session.setEvaluationRemarks(new HashMap<>());
                }
                for (Map.Entry<?, ?> e : incomingRemarks.entrySet()) {
                    if (e.getValue() != null) {
                        session.getEvaluationRemarks().put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
        }

        List<Problem> allProblems = problemRepository.findAll();
        Map<String, String> problemAnswers = allProblems.stream()
                .filter(p -> p.getCorrectOption() != null)
                .collect(Collectors.toMap(Problem::getId, Problem::getCorrectOption, (a, b) -> a));
        Map<String, Problem> problemMap = allProblems.stream()
                .collect(Collectors.toMap(Problem::getId, p -> p, (a, b) -> a));

        autoScoreProgrammingAnswers(session, problemMap, true);

        Map<String, Object> metrics = computeSubmissionMetrics(session, problemAnswers, problemMap);
        session.setTotalScore(((Number) metrics.get("totalScore")).doubleValue());
        session.setPercentage(((Number) metrics.get("percentage")).doubleValue());
        session.setTotalQuestions((Integer) metrics.get("totalQuestions"));
        session.setEvaluated(true);
        session.setLastUpdatedAt(System.currentTimeMillis());

        testSessionRepository.save(session);
        sessionCache.saveToCache(session);

        Map<String, Object> response = new HashMap<>(metrics);
        response.put("username", session.getUsername());
        response.put("evaluated", true);
        response.put("manualMarks", session.getManualMarks());
        response.put("remarks", session.getEvaluationRemarks());
        response.put("message", "Evaluation saved successfully for " + username);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/evaluate-all")
    /**
     * Grades one batch and reports what is left, so the caller can keep going.
     * A whole cohort is tens of thousands of sandbox runs and would hold a
     * single request open far past any proxy timeout. Already-evaluated
     * sessions are skipped, so an interrupted run simply resumes.
     */
    public ResponseEntity<?> evaluateAllSubmissions(
            @RequestParam(defaultValue = "10") int batchSize,
            @RequestParam(defaultValue = "false") boolean force) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()))) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied. Only Super Admins can evaluate submissions."));
        }

        int size = Math.max(1, Math.min(batchSize, 50));

        List<TestSession> submitted = testSessionRepository.findAll().stream()
                .filter(TestSession::isSubmitted)
                .collect(Collectors.toList());

        // Clearing the flag once turns a re-grade into an ordinary run, so the
        // caller keeps the same resumable batching and progress count.
        boolean regrade = force;
        if (force) {
            List<TestSession> toReset = submitted.stream()
                    .filter(TestSession::isEvaluated)
                    .peek(s -> s.setEvaluated(false))
                    .collect(Collectors.toList());
            if (!toReset.isEmpty()) {
                testSessionRepository.saveAll(toReset);
                toReset.forEach(sessionCache::saveToCache);
                log.info("[Evaluate] Re-grade requested, reopened {} paper(s)", toReset.size());
            }
        }

        List<TestSession> pending = submitted.stream()
                .filter(s -> !s.isEvaluated())
                .collect(Collectors.toList());

        List<TestSession> batch = pending.stream().limit(size).collect(Collectors.toList());

        if (batch.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "success", true, "processed", 0, "remaining", 0,
                    "totalSubmitted", submitted.size(), "done", true,
                    "message", "All " + submitted.size() + " submitted papers are already evaluated."));
        }

        List<Problem> allProblems = problemRepository.findAll();
        Map<String, String> problemAnswers = allProblems.stream()
                .filter(p -> p.getCorrectOption() != null)
                .collect(Collectors.toMap(Problem::getId, Problem::getCorrectOption, (a, b) -> a));
        Map<String, Problem> problemMap = allProblems.stream()
                .collect(Collectors.toMap(Problem::getId, p -> p, (a, b) -> a));

        for (TestSession session : batch) {
            autoScoreProgrammingAnswers(session, problemMap, regrade);
            Map<String, Object> metrics = computeSubmissionMetrics(session, problemAnswers, problemMap);
            session.setTotalScore(((Number) metrics.get("totalScore")).doubleValue());
            session.setPercentage(((Number) metrics.get("percentage")).doubleValue());
            session.setTotalQuestions((Integer) metrics.get("totalQuestions"));
            session.setEvaluated(true);
            session.setLastUpdatedAt(System.currentTimeMillis());
        }
        testSessionRepository.saveAll(batch);
        batch.forEach(sessionCache::saveToCache);

        int remaining = pending.size() - batch.size();
        log.info("[Evaluate] Graded {} papers, {} remaining", batch.size(), remaining);

        Map<String, User> userByUsername = userRepository.findAll().stream()
                .filter(u -> u.getUsername() != null)
                .collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));

        List<Map<String, Object>> gradedRows = batch.stream().map(s -> {
            User u = userByUsername.get(s.getUsername());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", s.getUsername());
            row.put("name", u != null && u.getName() != null ? u.getName() : s.getUsername());
            row.put("email", u != null && u.getEmail() != null ? u.getEmail() : "");
            row.put("score", s.getTotalScore());
            row.put("percentage", s.getPercentage());
            return row;
        }).collect(Collectors.toList());

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("processed", batch.size());
        body.put("remaining", remaining);
        body.put("totalSubmitted", submitted.size());
        body.put("done", remaining == 0);
        body.put("graded", gradedRows);
        body.put("message", "Graded " + batch.size() + " paper(s), " + remaining + " remaining.");
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{username}/force-submit")
    public ResponseEntity<?> forceSubmit(
            @PathVariable String username,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String domain) {
        Optional<TestSession> updated = sessionCache.forceSubmit(username, year, domain);
        if (updated.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No test session found for student " + username));
        }
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Test session for " + username + " has been forcefully submitted.",
            "submitted", true
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{username}/identity")
    public ResponseEntity<?> getStudentIdentity(@PathVariable String username) {
        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        User user = optUser.get();
        String faceUrl = r2Service.generatePresignedUrl(user.getFacePhotoUrl());
        String idCardUrl = r2Service.generatePresignedUrl(user.getIdCardPhotoUrl());

        return ResponseEntity.ok(Map.of(
            "facePhotoUrl", faceUrl != null ? faceUrl : "",
            "idCardPhotoUrl", idCardUrl != null ? idCardUrl : ""
        ));
    }

    
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/{username}")
    public ResponseEntity<?> deleteSubmission(
            @PathVariable String username,
            @RequestParam Integer year,
            @RequestParam String domain) {

        Optional<TestSession> stored = testSessionRepository
                .findByUsernameAndYearAndDomain(username, year, domain);
        if (stored.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No submission found for this student"));
        }

        
        
        sessionCache.evict(username, year, domain);
        testSessionRepository.delete(stored.get());

        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        log.warn("[Admin] {} deleted the submission of {} (year {}, {})", actor, username, year, domain);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Deleted the submission of " + username));
    }

    
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/blocked")
    public ResponseEntity<?> getBlockedStudents() {
        List<Map<String, Object>> blocked = new ArrayList<>();

        for (TestSession stored : testSessionRepository.findBySubmittedFalse()) {
            TestSession session = sessionCache
                    .getSession(stored.getUsername(), stored.getYear(), stored.getDomain())
                    .orElse(stored);

            if (!session.isBlocked()) continue;

            Map<String, Long> anomalyCounts = session.getAnomalies().stream()
                    .filter(a -> a.getType() != null)
                    .collect(Collectors.groupingBy(AnomalyLog::getType, Collectors.counting()));

            long lastEventAt = session.getAnomalies().stream()
                    .mapToLong(AnomalyLog::getTimestamp)
                    .max()
                    .orElse(session.getLastUpdatedAt());

            Map<String, Object> row = new HashMap<>();
            row.put("username", session.getUsername());
            row.put("email", userRepository.findByUsername(session.getUsername())
                    .map(User::getEmail).orElse(session.getUsername()));
            row.put("year", session.getYear());
            row.put("domain", session.getDomain());
            row.put("tabSwitchCount", session.getTabSwitchCount());
            row.put("screenshotCount", session.getScreenshotCount());
            row.put("appSwitchCount", session.getAppSwitchCount());
            row.put("anomalyCounts", anomalyCounts);
            row.put("totalAnomalies", session.getAnomalies().size());
            row.put("lastEventAt", lastEventAt);
            row.put("activeSection", session.getActiveSection());
            row.put("unblockRequested", anomalyCounts.getOrDefault("UNBLOCK_REQUESTED", 0L) > 0);
            blocked.add(row);
        }

        blocked.sort((a, b) -> Long.compare((Long) b.get("lastEventAt"), (Long) a.get("lastEventAt")));

        Map<String, Object> body = new HashMap<>();
        body.put("blocked", blocked);
        body.put("count", blocked.size());
        return ResponseEntity.ok(body);
    }

    
    /**
     * The same violation record as {@code /session}, rendered as a PDF an
     * invigilator can file or attach to an academic-integrity case.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{username}/report.pdf")
    public ResponseEntity<?> downloadViolationReport(@PathVariable String username) {
        Optional<TestSession> found = resolveReportableSession(username);
        if (found.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No session found for this student"));
        }

        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        byte[] pdf = violationReportPdfService.render(found.get(), actor);

        String safeName = username.replaceAll("[^A-Za-z0-9._-]", "_");
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition",
                        "attachment; filename=\"violation-report-" + safeName + ".pdf\"")
                .body(pdf);
    }

    /**
     * Prefers the exam still in progress, falling back to the most recent
     * one, and reads live cached state for an unsubmitted session so the
     * report isn't stale.
     */
    private Optional<TestSession> resolveReportableSession(String username) {
        List<TestSession> sessions = testSessionRepository.findByUsername(username);
        if (sessions.isEmpty()) return Optional.empty();

        TestSession session = sessions.stream()
                .filter(s -> !s.isSubmitted())
                .findFirst()
                .orElseGet(() -> sessions.stream()
                        .max(Comparator.comparingLong(TestSession::getCreatedAt))
                        .orElse(sessions.get(0)));

        if (!session.isSubmitted()) {
            session = sessionCache.getSession(username, session.getYear(), session.getDomain())
                    .orElse(session);
        }
        return Optional.of(session);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{username}/session")
    public ResponseEntity<?> getStudentSession(@PathVariable String username) {
        List<TestSession> sessions = testSessionRepository.findByUsername(username);
        if (sessions.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No session found for this student"));
        }

        
        TestSession session = sessions.stream()
                .filter(s -> !s.isSubmitted())
                .findFirst()
                .orElseGet(() -> sessions.stream()
                        .max(Comparator.comparingLong(TestSession::getCreatedAt))
                        .orElse(sessions.get(0)));

        
        if (!session.isSubmitted()) {
            session = sessionCache.getSession(username, session.getYear(), session.getDomain())
                    .orElse(session);
        }

        Map<String, Long> anomalyCounts = session.getAnomalies().stream()
                .filter(a -> a.getType() != null)
                .collect(Collectors.groupingBy(a -> a.getType(), Collectors.counting()));

        Map<String, Object> body = new HashMap<>();
        body.put("username", session.getUsername());
        body.put("year", session.getYear());
        body.put("domain", session.getDomain());
        body.put("submitted", session.isSubmitted());
        body.put("isBlocked", session.isBlocked());
        body.put("tabSwitchCount", session.getTabSwitchCount());
        body.put("screenshotCount", session.getScreenshotCount());
        body.put("appSwitchCount", session.getAppSwitchCount());
        body.put("faceMismatchCount", session.getFaceMismatchCount());
        body.put("voiceDetectionCount", session.getVoiceDetectionCount());
        body.put("anomalyCounts", anomalyCounts);
        body.put("anomalies", session.getAnomalies());
        return ResponseEntity.ok(body);
    }
}
