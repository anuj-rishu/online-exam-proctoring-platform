package in.srminsider.labs.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "test_sessions")
@CompoundIndex(name = "user_year_domain", def = "{'username': 1, 'year': 1, 'domain': 1}", unique = true)
public class TestSession {
    @Id
    private String id;

    private String username;
    private Integer year;
    private String domain;

    @Builder.Default
    private List<String> problemIds = new ArrayList<>();

    @Builder.Default
    private Map<String, String> answers = new HashMap<>();

    @Builder.Default
    private Map<String, String> questionStatuses = new HashMap<>();

    @Builder.Default
    private Map<String, Long> sectionStartTimes = new HashMap<>();

    @Builder.Default
    private Map<String, Long> sectionEndTimes = new HashMap<>();

    @Builder.Default
    private List<String> lockedSections = new ArrayList<>();

    
    @Builder.Default
    private List<String> sectionOrder = new ArrayList<>();

    @Builder.Default
    private int currentIndex = 0;

    private String activeSection;

    @Builder.Default
    private int tabSwitchCount = 0;


    @Builder.Default
    private int screenshotCount = 0;

    /**
     * Consecutive in-exam checks where the student was facing away from the
     * screen. Glancing away once is normal, so a violation is only recorded
     * once this crosses a threshold; it resets as soon as they face forward.
     */
    @Builder.Default
    private int consecutiveLookAways = 0;

    /**
     * Consecutive microphone samples carrying sustained speech. Debounced the
     * same way as {@link #consecutiveLookAways}: one cough or a passing noise
     * shouldn't become a violation, sustained talking should.
     */
    @Builder.Default
    private int consecutiveVoiceHits = 0;

    /**
     * Consecutive in-exam checks that failed to match the enrolled face.
     * Debounced like the two above: a hand over the mouth or a drink raised to
     * the face drops the similarity for a moment, which is not impersonation.
     */
    @Builder.Default
    private int consecutiveFaceMismatches = 0;

    @Builder.Default
    private int voiceDetectionCount = 0;

    /**
     * Times the exam window lost focus to another application while still
     * being on screen — alt-tabbing or another window taking focus over it.
     * Distinct from {@link #tabSwitchCount}, which covers switching browser
     * tabs or minimising.
     */
    @Builder.Default
    private int appSwitchCount = 0;

    /**
     * Times the live check found someone other than the enrolled student in
     * frame. Treated far more severely than the other signals — a couple of
     * these blocks the test outright, because it points at impersonation
     * rather than carelessness.
     */
    @Builder.Default
    private int faceMismatchCount = 0;

    
    private String assignedProctor;
    private long proctorClaimedAt;

    private long createdAt;
    private long lastUpdatedAt;

    @Builder.Default
    private boolean submitted = false;

    @Builder.Default
    private boolean autoSubmitted = false;

    private long submittedAt;

    @Builder.Default
    private List<AnomalyLog> anomalies = new ArrayList<>();

    @Builder.Default
    private boolean isBlocked = false;

    @Builder.Default
    private boolean hardwareValidated = false;

    private Integer totalQuestions;
    private Double totalScore;
    private Double percentage;

    @Builder.Default
    private boolean evaluated = false;

    @Builder.Default
    private Map<String, Integer> manualMarks = new HashMap<>();

    @Builder.Default
    private Map<String, String> evaluationRemarks = new HashMap<>();
}
