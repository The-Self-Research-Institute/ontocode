package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.Statement;
import self.research.ontology.owlEditor.service.SparqlQueryScope;

import java.util.List;

public interface InferenceSource {

    String revision(SparqlQueryScope scope, String userId);

    List<Statement> read(SparqlQueryScope scope, int maxStatements);
}
