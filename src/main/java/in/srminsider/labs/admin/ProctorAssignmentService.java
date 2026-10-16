package in.srminsider.labs.admin;

import in.srminsider.labs.domain.model.ProctorAssignment;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.repository.ProctorAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class ProctorAssignmentService {

    
    public static final long LEASE_TTL_MS = 90_000L;

    public static final List<Integer> ALLOWED_CAPACITIES = List.of(10, 20, 30, 40);

    private final ProctorAssignmentRepository assignmentRepository;
    private final MongoTemplate mongoTemplate;

    
    private volatile Map<String, String> claimIndex = Map.of();

    @Scheduled(fixedRate = 4000)
    public void refreshClaimIndex() {
        try {
            Query active = Query.query(new Criteria().andOperator(
                    Criteria.where("assignedProctor").ne(null),
                    Criteria.where("submitted").is(false),
                    Criteria.where("proctorClaimedAt").gte(System.currentTimeMillis() - LEASE_TTL_MS)));
            active.fields().include("username").include("assignedProctor");

            Map<String, String> next = new HashMap<>();
            for (TestSession session : mongoTemplate.find(active, TestSession.class)) {
                next.put(session.getUsername(), session.getAssignedProctor());
            }
            claimIndex = Map.copyOf(next);
        } catch (Exception e) {
            log.error("[Proctor] Failed to refresh claim index; keeping previous view", e);
        }
    }

    
    public String proctorFor(String studentUsername) {
        return studentUsername == null ? null : claimIndex.get(studentUsername);
    }

    public Optional<ProctorAssignment> configFor(String adminUsername) {
        return assignmentRepository.findByAdminUsername(adminUsername);
    }

    public List<ProctorAssignment> allConfigs() {
        return assignmentRepository.findAll();
    }

    public ProctorAssignment saveConfig(String adminUsername, List<String> domains, int capacity, String updatedBy) {
        ProctorAssignment config = assignmentRepository.findByAdminUsername(adminUsername)
                .orElseGet(() -> ProctorAssignment.builder().adminUsername(adminUsername).build());

        List<String> previousDomains = config.getDomains() == null ? List.of() : List.copyOf(config.getDomains());

        config.setDomains(domains);
        config.setCapacity(capacity);
        config.setUpdatedAt(System.currentTimeMillis());
        config.setUpdatedBy(updatedBy);
        ProctorAssignment saved = assignmentRepository.save(config);

        
        
        
        releaseClaimsOutsideScope(adminUsername, domains, capacity, previousDomains);
        return saved;
    }

    
    public List<TestSession> claimRoster(String adminUsername) {
        Optional<ProctorAssignment> maybeConfig = configFor(adminUsername);
        if (maybeConfig.isEmpty()) {
            return Collections.emptyList();
        }

        ProctorAssignment config = maybeConfig.get();
        List<String> domains = config.getDomains();
        if (domains == null || domains.isEmpty() || config.getCapacity() <= 0) {
            return Collections.emptyList();
        }

        releaseExpiredClaims();

        List<TestSession> held = renewHeldClaims(adminUsername, domains);

        int room = config.getCapacity() - held.size();
        for (int i = 0; i < room; i++) {
            TestSession claimed = claimOne(adminUsername, domains);
            if (claimed == null) break;
            held.add(claimed);
        }

        return held;
    }

    
    public void releaseExpiredClaims() {
        Query stale = Query.query(new Criteria().andOperator(
                Criteria.where("assignedProctor").ne(null),
                Criteria.where("proctorClaimedAt").lt(System.currentTimeMillis() - LEASE_TTL_MS)));

        mongoTemplate.updateMulti(stale, releaseUpdate(), TestSession.class);
    }

    public void releaseAll(String adminUsername) {
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("assignedProctor").is(adminUsername)),
                releaseUpdate(),
                TestSession.class);
    }

    
    public long liveLoad(String adminUsername) {
        return mongoTemplate.count(
                Query.query(new Criteria().andOperator(
                        Criteria.where("assignedProctor").is(adminUsername),
                        Criteria.where("submitted").is(false),
                        Criteria.where("proctorClaimedAt").gte(System.currentTimeMillis() - LEASE_TTL_MS))),
                TestSession.class);
    }

    
    public long unassignedCount(List<String> domains) {
        if (domains == null || domains.isEmpty()) return 0;
        return mongoTemplate.count(Query.query(unclaimedCriteria(domains)), TestSession.class);
    }

    private Update releaseUpdate() {
        return new Update().unset("assignedProctor").set("proctorClaimedAt", 0L);
    }

    private Criteria unclaimedCriteria(List<String> domains) {
        return new Criteria().andOperator(
                Criteria.where("submitted").is(false),
                Criteria.where("domain").in(domains),
                new Criteria().orOperator(
                        Criteria.where("assignedProctor").is(null),
                        Criteria.where("assignedProctor").exists(false)));
    }

    
    private TestSession claimOne(String adminUsername, List<String> domains) {
        Update claim = new Update()
                .set("assignedProctor", adminUsername)
                .set("proctorClaimedAt", System.currentTimeMillis());

        return mongoTemplate.findAndModify(
                Query.query(unclaimedCriteria(domains)),
                claim,
                FindAndModifyOptions.options().returnNew(true),
                TestSession.class);
    }

    
    private List<TestSession> renewHeldClaims(String adminUsername, List<String> domains) {
        List<TestSession> held = mongoTemplate.find(
                Query.query(Criteria.where("assignedProctor").is(adminUsername)),
                TestSession.class);

        List<TestSession> active = new ArrayList<>();
        for (TestSession session : held) {
            if (session.isSubmitted() || !domains.contains(session.getDomain())) {
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("id").is(session.getId())),
                        releaseUpdate(),
                        TestSession.class);
                continue;
            }
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("id").is(session.getId())),
                    new Update().set("proctorClaimedAt", System.currentTimeMillis()),
                    TestSession.class);
            session.setProctorClaimedAt(System.currentTimeMillis());
            active.add(session);
        }
        return active;
    }

    
    private void releaseClaimsOutsideScope(String adminUsername, List<String> domains, int capacity,
                                           List<String> previousDomains) {
        if (domains == null || domains.isEmpty() || capacity <= 0) {
            releaseAll(adminUsername);
            return;
        }

        mongoTemplate.updateMulti(
                Query.query(new Criteria().andOperator(
                        Criteria.where("assignedProctor").is(adminUsername),
                        Criteria.where("domain").nin(domains))),
                releaseUpdate(),
                TestSession.class);

        List<TestSession> stillHeld = mongoTemplate.find(
                Query.query(Criteria.where("assignedProctor").is(adminUsername))
                        .with(org.springframework.data.domain.Sort.by("proctorClaimedAt").ascending()),
                TestSession.class);

        for (int i = capacity; i < stillHeld.size(); i++) {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("id").is(stillHeld.get(i).getId())),
                    releaseUpdate(),
                    TestSession.class);
        }

        if (!previousDomains.equals(domains)) {
            log.info("[Proctor] {} scope changed {} -> {} (capacity {})",
                    adminUsername, previousDomains, domains, capacity);
        }
    }
}
