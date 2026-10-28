package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ExtractedFacts {

    final Set<Statement> structure = new LinkedHashSet<>();
    final Set<Statement> facts = new LinkedHashSet<>();
    final Map<IRI, List<IRI>> namesOf = new LinkedHashMap<>();
    final List<IRI[]> differentPairs = new ArrayList<>();
    final List<String> oversizeGroups = new ArrayList<>();
    int groupCount;
    int largestGroup = 1;
}
