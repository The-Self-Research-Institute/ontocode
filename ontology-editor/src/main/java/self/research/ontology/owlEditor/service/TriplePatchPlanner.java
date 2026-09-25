package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.util.Values;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
final class TriplePatchPlanner {

    private static final long MAX_PATCH_LINES = 250_000;

    record Edit(long startLine, int lineCount, int newLineCount) {}

    record TriplePatch(Model removed, Model added, Map<IRI, Integer> treeDeleteDepth, Model insertedTrees,
                       Model restoredTrees, Model kept, Set<IRI> subjects, Model expected, int verifyDepth,
                       SubjectRangeIndex newIndex) {}

    Optional<TriplePatch> plan(String format, Path oldFile, Path newFile, List<Edit> edits, String baseUri)
            throws IOException {
        if (oldFile == null || newFile == null || edits.isEmpty() || !CodeViewSubjectIndex.supports(format)) {
            return Optional.empty();
        }
        SubjectRangeIndex oldIndex = CodeViewSubjectIndex.forFile(oldFile, format).orElse(null);
        SubjectRangeIndex newIndex = newIndexFor(oldIndex, edits, newFile, format);
        if (oldIndex == null || !oldIndex.complete() || !newIndex.complete()) {
            return Optional.empty();
        }
        Set<Long> headerOnly = PrefixOnlyEdits.find(format, edits, oldIndex, newIndex, newFile);
        if (headerOnly == null) {
            return Optional.empty();
        }
        PatchSpans spans = PatchSpans.compute(edits, oldIndex, newIndex, headerOnly);
        if (spans == null || spans.totalOldLines() > MAX_PATCH_LINES) {
            return Optional.empty();
        }
        Set<IRI> subjects = new LinkedHashSet<>();
        if (!collectSubjects(oldIndex, spans.oldSpans(), subjects) || !collectSubjects(newIndex, spans.newSpans(), subjects)) {
            return Optional.empty();
        }
        PatchFragments fragments = new PatchFragments(format, baseUri);
        List<String> oldTexts = fragments.read(oldFile, spans.oldSpans(), oldIndex);
        List<String> newTexts = fragments.read(newFile, spans.newSpans(), newIndex);
        if (fragments.hasLabelledBlankNodes(oldTexts) || fragments.hasLabelledBlankNodes(newTexts)) {
            return Optional.empty();
        }
        Model oldModel = fragments.parseAll(oldTexts, oldIndex);
        Model newModel = fragments.parseAll(newTexts, newIndex);
        if (oldModel == null || newModel == null || !subjectsStayWithin(oldModel, subjects)
                || !subjectsStayWithin(newModel, subjects)) {
            return Optional.empty();
        }
        return buildPatch(subjects, oldModel, newModel, oldIndex, newIndex, spans, fragments, oldFile, newFile);
    }

    private Optional<TriplePatch> buildPatch(Set<IRI> subjects, Model oldModel, Model newModel,
                                             SubjectRangeIndex oldIndex, SubjectRangeIndex newIndex, PatchSpans spans,
                                             PatchFragments fragments, Path oldFile, Path newFile) throws IOException {
        Model kept = new LinkedHashModel();
        Model insertedTrees = new LinkedHashModel();
        Model restoredTrees = new LinkedHashModel();
        Map<IRI, Integer> treeDeleteDepth = new HashMap<>();
        int maxDepth = 0;
        for (IRI subject : subjects) {
            Model oldTree = BlankNodeTrees.treeOf(oldModel, subject);
            Model newTree = BlankNodeTrees.treeOf(newModel, subject);
            List<SubjectRangeIndex.Block> others = outside(oldIndex.blocksFor(subject.stringValue()), spans.oldSpans());
            if ((!oldTree.isEmpty() || !newTree.isEmpty()) && !others.isEmpty()) {
                return Optional.empty();
            }
            if (!others.isEmpty()) {
                Model otherModel = fragments.parseBlocks(oldFile, others, oldIndex);
                if (otherModel == null) {
                    return Optional.empty();
                }
                kept.addAll(BlankNodeTrees.direct(otherModel.filter(subject, null, null)));
            }
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
        }
        Model oldDirect = BlankNodeTrees.direct(oldModel);
        Model newDirect = BlankNodeTrees.direct(newModel);
        Model removed = new LinkedHashModel(oldDirect);
        removed.removeAll(newDirect);
        removed.removeAll(kept);
        Model added = new LinkedHashModel(newDirect);
        added.removeAll(oldDirect);
        Model expected = expectedAfter(subjects, newIndex, fragments, newFile);
        if (expected == null) {
            return Optional.empty();
        }
        return Optional.of(new TriplePatch(removed, added, treeDeleteDepth, insertedTrees, restoredTrees, kept,
                subjects, expected, maxDepth + 1, newIndex));
    }

    private static SubjectRangeIndex newIndexFor(SubjectRangeIndex oldIndex, List<Edit> edits, Path newFile,
                                                 String format) throws IOException {
        if (oldIndex == null) {
            return CodeViewSubjectIndex.build(newFile, format);
        }
        long started = System.nanoTime();
        Optional<SubjectRangeIndex> derived = IncrementalSubjectIndex.derive(oldIndex, edits, newFile, format);
        SubjectRangeIndex index = derived.isPresent() ? derived.get() : CodeViewSubjectIndex.build(newFile, format);
        log.info("[PERF] Patch plan new-file index: {} in {}ms", derived.isPresent() ? "incremental" : "full rebuild",
                (System.nanoTime() - started) / 1_000_000);
        return index;
    }

    private Model expectedAfter(Set<IRI> subjects, SubjectRangeIndex newIndex, PatchFragments fragments, Path newFile)
            throws IOException {
        List<SubjectRangeIndex.Block> blocks = new ArrayList<>();
        for (IRI subject : subjects) {
            blocks.addAll(newIndex.blocksFor(subject.stringValue()));
        }
        Model parsed = fragments.parseBlocks(newFile, blocks, newIndex);
        if (parsed == null) {
            return null;
        }
        Model expected = new LinkedHashModel();
        for (IRI subject : subjects) {
            expected.addAll(BlankNodeTrees.closure(parsed, subject));
        }
        return expected;
    }

    private static boolean collectSubjects(SubjectRangeIndex index, List<long[]> spans, Set<IRI> subjects) {
        for (long[] span : spans) {
            for (SubjectRangeIndex.Block block : index.overlapping(span[0], span[1])) {
                if (block.isBlankSubject()) {
                    return false;
                }
                subjects.add(Values.iri(block.subject()));
            }
        }
        return true;
    }

    private static boolean subjectsStayWithin(Model model, Set<IRI> subjects) {
        for (Statement st : model) {
            Resource subject = st.getSubject();
            if (!(subject instanceof BNode) && !subjects.contains(subject)) {
                return false;
            }
        }
        return true;
    }

    private static List<SubjectRangeIndex.Block> outside(List<SubjectRangeIndex.Block> blocks, List<long[]> spans) {
        List<SubjectRangeIndex.Block> result = new ArrayList<>();
        for (SubjectRangeIndex.Block block : blocks) {
            boolean inside = false;
            for (long[] span : spans) {
                if (block.startLine() >= span[0] && block.endLine() <= span[1]) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                result.add(block);
            }
        }
        return result;
    }
}
