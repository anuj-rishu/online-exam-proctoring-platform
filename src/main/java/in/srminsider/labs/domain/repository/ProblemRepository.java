package in.srminsider.labs.domain.repository;

import in.srminsider.labs.domain.model.Problem;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ProblemRepository extends MongoRepository<Problem, String> {
    List<Problem> findByYearAndDomain(Integer year, String domain);
}
