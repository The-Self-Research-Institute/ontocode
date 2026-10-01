package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.util.SubjectBlocks;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;
import self.research.ontology.owlEditor.util.TurtleSubjectBlockReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantPatchBenchmark {

    private static final String GRAPH = "urn:bench:graph";
    private static final String HEADER = String.join("\n",
            "@prefix ex: <http://example.org/bench/> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            "");
    private static final int HEADER_LINES = 3;
    private static final int LINES_PER_BLOCK = 3;

    private record Row(int megabytes, long triples, long fullParse, long fullReload, long twoModelDiff,
                       long indexBuild, long streamedRead, long indexedRead, long patchPlan, long patchApply) {}

    @Test
    @SuppressWarnings("unchecked")
    void measureFullReimportAgainstTriplePatch() throws Exception {
        measure(1);
        System.out.printf("[PERF] bench heap=%dMB%n", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("[PERF] bench MB | triples | fullParse | fullReload | twoModelDiff | indexBuild | "
                + "streamedRead | indexedRead | patchPlan | patchApply (ms, -1 = out of memory)");
        for (String size : System.getProperty("bench.sizes", "1,10,50,100").split(",")) {
            Row r = measure(Integer.parseInt(size.trim()));
            System.out.printf("[PERF] bench %d | %d | %d | %d | %d | %d | %d | %d | %d | %d%n",
                    r.megabytes(), r.triples(), r.fullParse(), r.fullReload(), r.twoModelDiff(), r.indexBuild(),
                    r.streamedRead(), r.indexedRead(), r.patchPlan(), r.patchApply());
            System.out.flush();
        }
    }

    private Row measure(int megabytes) throws Exception {
        Path oldFile = Files.createTempFile("bench-old-", ".ttl");
        Path newFile = Files.createTempFile("bench-new-", ".ttl");
        Repository repository = new SailRepository(new MemoryStore());
        try {
            int blocks = generate(oldFile, megabytes, -1);
            int target = blocks / 2;
            generate(newFile, megabytes, target);
            String subject = "http://example.org/bench/C" + target;
            long startLine = HEADER_LINES + (long) target * LINES_PER_BLOCK + 1;

            Model[] parsed = new Model[1];
            long fullParse = time(() -> parsed[0] = parseAll(oldFile));
            long fullReload = parsed[0] == null ? -1 : time(() -> reload(repository, parsed[0]));
            long triples = parsed[0] == null ? 0 : parsed[0].size();
            parsed[0] = null;
            long twoModelDiff = time(() -> diffSize(parseAll(oldFile), parseAll(newFile)));
            SubjectRangeIndex[] index = new SubjectRangeIndex[1];
            long indexBuild = time(() -> index[0] = CodeViewSubjectIndex.build(oldFile, "turtle"));
            long streamedRead = time(() -> streamedBlocks(oldFile, subject));
            long indexedRead = time(() -> indexedBlock(oldFile, index[0], subject));

            TriplePatchPlanner planner = new TriplePatchPlanner();
            List<TriplePatchPlanner.Edit> edits = List.of(new TriplePatchPlanner.Edit(startLine, 1, 1));
            Optional<TriplePatchPlanner.TriplePatch>[] plan = new Optional[1];
            long patchPlan = time(() -> plan[0] = planner.plan("turtle", oldFile, newFile, edits, GRAPH));
            assertTrue(plan[0].isPresent(), "benchmark edit should be patchable");
            SparqlDatasetService datasetService = mock(SparqlDatasetService.class);
            when(datasetService.graphTarget("bench")).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, GRAPH));
            TriplePatchExecutor executor = new TriplePatchExecutor(datasetService);
            long patchApply = time(() -> {
                assertEquals(TriplePatchExecutor.Outcome.APPLIED, executor.apply("bench", plan[0].get()));
                return 0;
            });
            return new Row(megabytes, triples, fullParse, fullReload, twoModelDiff, indexBuild,
                    streamedRead, indexedRead, patchPlan, patchApply);
        } finally {
            repository.shutDown();
            Files.deleteIfExists(oldFile);
            Files.deleteIfExists(newFile);
        }
    }

    private static int generate(Path file, int megabytes, int changedBlock) throws IOException {
        long targetBytes = megabytes * 1024L * 1024L;
        long written = 0;
        int block = 0;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            written += HEADER.length();
            while (written < targetBytes) {
                String label = block == changedBlock ? "Changed " + block : "Class " + block;
                String text = "ex:C" + block + " a owl:Class ;\n    rdfs:label \"" + label + "\"@en ;\n"
                        + "    rdfs:subClassOf ex:C" + (block / 2) + " .\n";
                out.write(text);
                written += text.length();
                block++;
            }
        }
        return block;
    }

    private static Model parseAll(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return Rio.parse(in, GRAPH, RDFFormat.TURTLE);
        }
    }

    private static long reload(Repository repository, Model model) {
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.begin();
            conn.clear(Values.iri(GRAPH));
            conn.add(model, Values.iri(GRAPH));
            conn.commit();
            return model.size();
        }
    }

    private static long diffSize(Model before, Model after) {
        Model removed = new LinkedHashModel(before);
        removed.removeAll(after);
        Model added = new LinkedHashModel(after);
        added.removeAll(before);
        return removed.size() + added.size();
    }

    private static long streamedBlocks(Path file, String subject) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return TurtleSubjectBlockReader.read(reader, subject, new SubjectBlocks.Limits(20, 400, 40_000)).blocks().size();
        }
    }

    private static long indexedBlock(Path file, SubjectRangeIndex index, String subject) throws IOException {
        SubjectRangeIndex.Block block = index.blocksFor(subject).get(0);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (long line = 0; line < block.startLine(); line++) {
                reader.readLine();
            }
            long read = 0;
            for (long line = block.startLine(); line <= block.endLine(); line++) {
                read += reader.readLine().length();
            }
            return read;
        }
    }

    private interface Step {
        Object run() throws Exception;
    }

    private static long time(Step step) throws Exception {
        System.gc();
        long start = System.nanoTime();
        try {
            step.run();
        } catch (OutOfMemoryError e) {
            return -1;
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}
