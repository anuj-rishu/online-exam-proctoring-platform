package in.srminsider.labs.domain.repository;

import in.srminsider.labs.domain.model.ProctorAssignment;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProctorAssignmentRepository extends MongoRepository<ProctorAssignment, String> {
    Optional<ProctorAssignment> findByAdminUsername(String adminUsername);
    void deleteByAdminUsername(String adminUsername);
}
