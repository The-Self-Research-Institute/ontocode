package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.IssueReport;

import java.util.List;

@Repository
public interface IssueReportRepository extends MongoRepository<IssueReport, String> {
    
    List<IssueReport> findByUserEmailOrderByCreatedAtDesc(String userEmail);
    
    List<IssueReport> findByProjectIdOrderByCreatedAtDesc(String projectId);
    
    List<IssueReport> findByStatusOrderByCreatedAtDesc(IssueReport.IssueStatus status);
}
