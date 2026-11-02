package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

final class RuleGraphScope {

    private static final IRI IMP = SimpleValueFactory.getInstance().createIRI("http://www.w3.org/2003/11/swrl#Imp");
    private static final IRI VARIABLE = SimpleValueFactory.getInstance().createIRI("http://www.w3.org/2003/11/swrl#Variable");

    private static final java.util.List<IRI> DECLARATIONS = java.util.List.of(
            org.eclipse.rdf4j.model.vocabulary.OWL.CLASS, org.eclipse.rdf4j.model.vocabulary.OWL.OBJECTPROPERTY,
            org.eclipse.rdf4j.model.vocabulary.OWL.DATATYPEPROPERTY, org.eclipse.rdf4j.model.vocabulary.OWL.ANNOTATIONPROPERTY,
            org.eclipse.rdf4j.model.vocabulary.RDFS.DATATYPE, org.eclipse.rdf4j.model.vocabulary.OWL.NAMEDINDIVIDUAL);

    private RuleGraphScope() {
    }

    static Model[] pair(Model before, Model after) {
        Model oldCore = core(before);
        Model newCore = core(after);
        Set<IRI> mentioned = new LinkedHashSet<>();
        mention(oldCore, mentioned);
        mention(newCore, mentioned);
        mentioned.add(org.eclipse.rdf4j.model.vocabulary.OWL.SAMEAS);
        mentioned.add(org.eclipse.rdf4j.model.vocabulary.OWL.DIFFERENTFROM);
        declare(before, oldCore, mentioned);
        declare(after, newCore, mentioned);
        return new Model[] {oldCore, newCore};
    }

    private static void mention(Model core, Set<IRI> mentioned) {
        for (Statement statement : core) {
            mentioned.add(statement.getPredicate());
            if (statement.getObject() instanceof IRI object) {
                mentioned.add(object);
            }
        }
    }

    private static void declare(Model full, Model into, Set<IRI> mentioned) {
        for (IRI entity : mentioned) {
            for (IRI type : DECLARATIONS) {
                into.addAll(full.filter(entity, RDF.TYPE, type));
            }
        }
    }

    private static Model core(Model full) {
        Set<Resource> roots = new LinkedHashSet<>();
        full.filter(null, RDF.TYPE, IMP).subjects().forEach(roots::add);
        full.filter(null, RDF.TYPE, VARIABLE).subjects().forEach(roots::add);
        Model scoped = new LinkedHashModel();
        Deque<Resource> queue = new ArrayDeque<>(roots);
        Set<Resource> seen = new HashSet<>(roots);
        while (!queue.isEmpty()) {
            Resource subject = queue.poll();
            for (Statement statement : full.filter(subject, null, null)) {
                scoped.add(statement);
                if (statement.getObject() instanceof BNode node && seen.add(node)) {
                    queue.add(node);
                }
            }
        }
        return scoped;
    }
}
