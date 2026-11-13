package self.research.ontology.auth.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcOntologyFileNameRepository implements OntologyFileNameRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcOntologyFileNameRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcOntologyFileNameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int updateFileName(String ontologyFileId, String fileName) {
        try {
            return jdbc.update("update ontology_file set file_name = ? where id = ?", fileName, ontologyFileId);
        } catch (BadSqlGrammarException e) {
            LOG.debug("[Rename] ontology_file is not available yet, nothing to sync for {}", ontologyFileId);
            return 0;
        }
    }
}
