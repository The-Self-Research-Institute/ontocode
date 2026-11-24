package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingImportStandInsTest {

    private static Path fixture(Path dir) throws Exception {
        Path f = dir.resolve("t.owl");
        try (var in = MissingImportStandInsTest.class.getResourceAsStream("/owl/nested-restriction-skipped-import.owl")) {
            Files.write(f, in.readAllBytes());
        }
        return f;
    }

    private static OWLOntology load(Path file) throws Exception {
        return MissingImportStandIns.load(OWLFormatConverter.createManagerWithSilentImports(), file);
    }

    @Test
    void plainLoaderWithSkippedImportReproducesTheOriginalBug(@TempDir Path dir) throws Exception {
        OWLOntology o = OWLFormatConverter.createManagerWithSilentImports()
                .loadOntologyFromOntologyDocument(fixture(dir).toFile());
        assertTrue(MissingImportStandIns.hasErrorPlaceholders(o));
    }

    @Test
    void skippedImportDoesNotTurnNestedClassIntoErrorPlaceholder(@TempDir Path dir) throws Exception {
        OWLOntology o = load(fixture(dir));
        assertFalse(MissingImportStandIns.hasErrorPlaceholders(o));
        assertTrue(o.axioms().anyMatch(a -> a.toString().contains("SO_0002054")
                && a.toString().contains("ObjectExactCardinality")));
    }

    @Test
    void declaresOnlyTheClassTheImportWouldHaveDescribed(@TempDir Path dir) throws Exception {
        OWLOntology o = load(fixture(dir));
        assertTrue(o.axioms().anyMatch(a -> a.toString().startsWith("Declaration(Class(<http://purl.obolibrary.org/obo/SO_0002054>))")));
        assertTrue(o.axioms().anyMatch(a -> a.toString().startsWith("Declaration(ObjectProperty(<http://purl.obolibrary.org/obo/IDO_0000664>))")));
        assertTrue(o.getImportsDeclarations().stream()
                .anyMatch(d -> d.getIRI().toString().equals("http://purl.obolibrary.org/obo/doid/obo/ext.owl")));
    }

    @Test
    void nonRdfXmlInputStillLoads(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.ofn");
        Files.writeString(f, "Prefix(:=<http://ex.org/>)\nOntology(<http://ex.org/o>\nDeclaration(Class(:A))\n)\n");
        OWLOntology o = load(f);
        assertTrue(o.axioms().anyMatch(a -> a.toString().contains("http://ex.org/A")));
    }

    @Test
    void importWordIsFoundEvenWhenItStraddlesTheReadBuffer(@TempDir Path dir) throws Exception {
        for (int offset : new int[] {0, 1, 3, 6}) {
            byte[] pad = new byte[(1 << 16) - 3 - offset];
            java.util.Arrays.fill(pad, (byte) 'x');
            Path f = dir.resolve("b" + offset + ".owl");
            Files.write(f, concat(pad, "imports".getBytes()));
            assertTrue(MissingImportStandIns.mentionsImports(f));
        }
    }

    @Test
    void fileWithoutTheImportWordIsNotScanned(@TempDir Path dir) throws Exception {
        byte[] pad = new byte[(1 << 16) * 2 + 17];
        java.util.Arrays.fill(pad, (byte) 'x');
        Path f = dir.resolve("none.owl");
        Files.write(f, concat(pad, "import".getBytes()));
        assertFalse(MissingImportStandIns.mentionsImports(f));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
