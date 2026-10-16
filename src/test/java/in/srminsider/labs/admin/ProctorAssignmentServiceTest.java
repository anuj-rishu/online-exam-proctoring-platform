package in.srminsider.labs.admin;

import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.repository.ProctorAssignmentRepository;
import in.srminsider.labs.domain.repository.TestSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
class ProctorAssignmentServiceTest {

    private static final String PREFIX = "proctor-alloc-test-";
    
    private static final String DOMAIN_ONE = "ProctorAllocTestDomainOne";
    private static final String DOMAIN_TWO = "ProctorAllocTestDomainTwo";
    private static final String ADMIN_A = PREFIX + "admin-a@example.invalid";
    private static final String ADMIN_B = PREFIX + "admin-b@example.invalid";

    @Autowired
    private ProctorAssignmentService service;

    @Autowired
    private ProctorAssignmentRepository assignmentRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void reset() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getUsername() != null && s.getUsername().startsWith(PREFIX))
                .toList());
        assignmentRepository.findByAdminUsername(ADMIN_A).ifPresent(assignmentRepository::delete);
        assignmentRepository.findByAdminUsername(ADMIN_B).ifPresent(assignmentRepository::delete);
    }

    private void createLiveStudents(String domain, int count) {
        for (int i = 0; i < count; i++) {
            sessionRepository.save(TestSession.builder()
                    .username(PREFIX + domain.replace(" ", "") + "-" + i + "@example.invalid")
                    .year(1)
                    .domain(domain)
                    .submitted(false)
                    .createdAt(System.currentTimeMillis())
                    .build());
        }
    }

    @Test
    void aProctorOnlyReceivesStudentsFromTheirAssignedDomains() {
        createLiveStudents(DOMAIN_ONE, 3);
        createLiveStudents(DOMAIN_TWO, 3);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 10, "super");
        List<TestSession> roster = service.claimRoster(ADMIN_A);

        assertThat(roster).hasSize(3);
        assertThat(roster).allSatisfy(s -> assertThat(s.getDomain()).isEqualTo(DOMAIN_ONE));
    }

    @Test
    void aProctorNeverHoldsMoreStudentsThanTheirCapacity() {
        createLiveStudents(DOMAIN_ONE, 25);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 10, "super");

        assertThat(service.claimRoster(ADMIN_A)).hasSize(10);
        
        assertThat(service.claimRoster(ADMIN_A)).hasSize(10);
        assertThat(service.liveLoad(ADMIN_A)).isEqualTo(10);
    }

    @Test
    void twoProctorsNeverReceiveTheSameStudent() {
        createLiveStudents(DOMAIN_ONE, 12);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 10, "super");
        service.saveConfig(ADMIN_B, List.of(DOMAIN_ONE), 10, "super");

        Set<String> a = new HashSet<>(service.claimRoster(ADMIN_A).stream().map(TestSession::getUsername).toList());
        Set<String> b = new HashSet<>(service.claimRoster(ADMIN_B).stream().map(TestSession::getUsername).toList());

        assertThat(a).isNotEmpty();
        assertThat(b).isNotEmpty();
        assertThat(a).as("a student must never be handed to two proctors").doesNotContainAnyElementsOf(b);
        assertThat(a.size() + b.size()).isEqualTo(12);
    }

    @Test
    void concurrentClaimsStillProduceDisjointRosters() throws Exception {
        createLiveStudents(DOMAIN_ONE, 40);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 20, "super");
        service.saveConfig(ADMIN_B, List.of(DOMAIN_ONE), 20, "super");

        
        
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<List<String>>> tasks = List.of(
                    () -> service.claimRoster(ADMIN_A).stream().map(TestSession::getUsername).toList(),
                    () -> service.claimRoster(ADMIN_B).stream().map(TestSession::getUsername).toList());

            List<String> all = new ArrayList<>();
            for (Future<List<String>> f : pool.invokeAll(tasks)) {
                all.addAll(f.get());
            }

            assertThat(all)
                    .as("no student may appear on two rosters even under concurrent claims")
                    .doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAbandonedClaimReturnsTheStudentToThePool() {
        createLiveStudents(DOMAIN_ONE, 2);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 10, "super");
        assertThat(service.claimRoster(ADMIN_A)).hasSize(2);

        
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("assignedProctor").is(ADMIN_A)),
                new Update().set("proctorClaimedAt",
                        System.currentTimeMillis() - ProctorAssignmentService.LEASE_TTL_MS - 1000),
                TestSession.class);

        service.saveConfig(ADMIN_B, List.of(DOMAIN_ONE), 10, "super");
        assertThat(service.claimRoster(ADMIN_B))
                .as("students must not be left unwatched when a proctor drops off")
                .hasSize(2);
    }

    @Test
    void narrowingTheScopeHandsBackStudentsTheProctorMayNoLongerWatch() {
        createLiveStudents(DOMAIN_ONE, 2);
        createLiveStudents(DOMAIN_TWO, 2);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE, DOMAIN_TWO), 10, "super");
        assertThat(service.claimRoster(ADMIN_A)).hasSize(4);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 10, "super");

        List<TestSession> roster = service.claimRoster(ADMIN_A);
        assertThat(roster).hasSize(2);
        assertThat(roster).allSatisfy(s -> assertThat(s.getDomain()).isEqualTo(DOMAIN_ONE));
        assertThat(service.unassignedCount(List.of(DOMAIN_TWO)))
                .as("the second-domain students must be back in the pool")
                .isEqualTo(2);
    }

    @Test
    void loweringCapacityReleasesTheOverflow() {
        createLiveStudents(DOMAIN_ONE, 20);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 20, "super");
        assertThat(service.claimRoster(ADMIN_A)).hasSize(20);

        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 10, "super");
        assertThat(service.liveLoad(ADMIN_A)).isEqualTo(10);
    }

    @Test
    void anAdminWithNoConfiguredScopeProctorsNobody() {
        createLiveStudents(DOMAIN_ONE, 5);

        assertThat(service.claimRoster(ADMIN_A)).isEmpty();
        assertThat(service.proctorFor(PREFIX + "WebDev-0@example.invalid")).isNull();
    }

    @Test
    void theClaimIndexReportsWhoHoldsEachStudent() {
        createLiveStudents(DOMAIN_ONE, 2);
        service.saveConfig(ADMIN_A, List.of(DOMAIN_ONE), 10, "super");
        List<TestSession> roster = service.claimRoster(ADMIN_A);

        service.refreshClaimIndex();

        for (TestSession s : roster) {
            assertThat(service.proctorFor(s.getUsername())).isEqualTo(ADMIN_A);
        }
    }
}
