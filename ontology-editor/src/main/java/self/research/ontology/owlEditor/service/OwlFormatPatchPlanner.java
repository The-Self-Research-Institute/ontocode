package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
final class OwlFormatPatchPlanner {

    private static final int MAX_CHANGED_SUBJECTS = 2_000;
    private static final Set<String> FORMATS = Set.of("owlxml", "manchester", "manchestersyntax", "functional",
            "functionalsyntax");

    private record Ownership(Map<IRI, Model> trees, Model unowned, boolean shared) {}

    static boolean supports(String format) {
        return format != null && FORMATS.contains(format.toLowerCase(Locale.ROOT));
    }

    Optional<TriplePatchPlanner.TriplePatch> plan(Path oldFile, Path newFile, String baseUri) throws IOException {
        long started = System.nanoTime();
        Model oldModel = toModel(oldFile, baseUri);
        Model newModel = toModel(newFile, baseUri);
        Ownership oldOwned = ownership(oldModel);
        Ownership newOwned = ownership(newModel);
        if (oldOwned.shared() || newOwned.shared() || !Models.isomorphic(oldOwned.unowned(), newOwned.unowned())) {
            return Optional.empty();
        }
        Set<IRI> changed = changedSubjects(oldModel, newModel, oldOwned, newOwned);
        if (changed.size() > MAX_CHANGED_SUBJECTS) {
            return Optional.empty();
        }
        log.info("[PERF] OWL-format patch plan: {} changed subjects of {} + {} triples in {}ms", changed.size(),
                oldModel.size(), newModel.size(), (System.nanoTime() - started) / 1_000_000);
        return Optional.of(build(changed, oldModel, newModel, oldOwned, newOwned));
    }

    private static Model toModel(Path file, String baseUri) throws IOException {
        Path converted;
        try {
            converted = OWLFormatConverter.convertToRDFXML(file);
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
        try (InputStream in = Files.newInputStream(converted)) {
            return Rio.parse(in, baseUri, RDFFormat.RDFXML);
        } finally {
            Files.deleteIfExists(converted);
        }
    }

    private static Ownership ownership(Model model) {
        Map<IRI, Model> trees = new HashMap<>();
        Map<Resource, IRI> owner = new HashMap<>();
        boolean shared = false;
        for (Resource subject : model.subjects()) {
            if (subject instanceof IRI iri) {
                Model tree = BlankNodeTrees.treeOf(model, iri);
                trees.put(iri, tree);
                for (Statement st : tree) {
                    IRI previous = owner.put(st.getSubject(), iri);
                    shared |= previous != null && !previous.equals(iri);
                }
            }
        }
        Model unowned = new LinkedHashModel();
        for (Statement st : model) {
            if (st.getSubject() instanceof BNode && !owner.containsKey(st.getSubject())) {
                unowned.add(st);
                shared |= st.getObject() instanceof BNode child && owner.containsKey(child);
            }
        }
        return new Ownership(trees, unowned, shared);
    }

    private static Set<IRI> changedSubjects(Model oldModel, Model newModel, Ownership oldOwned, Ownership newOwned) {
        Set<IRI> subjects = new LinkedHashSet<>(oldOwned.trees().keySet());
        subjects.addAll(newOwned.trees().keySet());
        Set<IRI> changed = new LinkedHashSet<>();
        for (IRI subject : subjects) {
            Model oldDirect = BlankNodeTrees.direct(oldModel.filter(subject, null, null));
            Model newDirect = BlankNodeTrees.direct(newModel.filter(subject, null, null));
            Model oldTree = oldOwned.trees().getOrDefault(subject, new LinkedHashModel());
            Model newTree = newOwned.trees().getOrDefault(subject, new LinkedHashModel());
            if (!new HashSet<>(oldDirect).equals(new HashSet<>(newDirect)) || !Models.isomorphic(oldTree, newTree)) {
                changed.add(subject);
            }
        }
        return changed;
    }

    private static TriplePatchPlanner.TriplePatch build(Set<IRI> subjects, Model oldModel, Model newModel,
                                                        Ownership oldOwned, Ownership newOwned) {
        Model removed = new LinkedHashModel();
        Model added = new LinkedHashModel();
        Model insertedTrees = new LinkedHashModel();
        Model restoredTrees = new LinkedHashModel();
        Model expected = new LinkedHashModel();
        Map<IRI, Integer> treeDeleteDepth = new HashMap<>();
        int maxDepth = 0;
        for (IRI subject : subjects) {
            Model oldDirect = BlankNodeTrees.direct(oldModel.filter(subject, null, null));
            Model newDirect = BlankNodeTrees.direct(newModel.filter(subject, null, null));
            Model oldTree = oldOwned.trees().getOrDefault(subject, new LinkedHashModel());
            Model newTree = newOwned.trees().getOrDefault(subject, new LinkedHashModel());
            oldDirect.stream().filter(st -> !newDirect.contains(st)).forEach(removed::add);
            newDirect.stream().filter(st -> !oldDirect.contains(st)).forEach(added::add);
            int oldDepth = BlankNodeTrees.depth(oldTree, subject);
            int newDepth = BlankNodeTrees.depth(newTree, subject);
            maxDepth = Math.max(maxDepth, Math.max(oldDepth, newDepth));
            if (!Models.isomorphic(oldTree, newTree)) {
                if (!oldTree.isEmpty()) {
                    treeDeleteDepth.put(subject, oldDepth);
                }
                insertedTrees.addAll(newTree);
                restoredTrees.addAll(oldTree);
            }
            expected.addAll(BlankNodeTrees.closure(newModel, subject));
        }
        return new TriplePatchPlanner.TriplePatch(removed, added, treeDeleteDepth, insertedTrees, restoredTrees,
                new LinkedHashModel(), subjects, expected, maxDepth + 1, null);
    }
}
