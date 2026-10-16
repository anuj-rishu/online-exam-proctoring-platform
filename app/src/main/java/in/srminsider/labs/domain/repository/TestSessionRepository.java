package in.srminsider.labs.domain.repository;

import in.srminsider.labs.domain.model.TestSession;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TestSessionRepository extends MongoRepository<TestSession, String> {
    Optional<TestSession> findByUsernameAndYearAndDomain(String username, Integer year, String domain);
    List<TestSession> findBySubmittedFalse();
    List<TestSession> findByUsername(String username);
    List<TestSession> findByYearAndDomain(Integer year, String domain);
    org.springframework.data.domain.Page<TestSession> findByUsernameIn(
            java.util.Collection<String> usernames, org.springframework.data.domain.Pageable pageable);
}
