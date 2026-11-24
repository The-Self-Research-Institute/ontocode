package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class MissingImportStandIns {

    private static final Logger log = LoggerFactory.getLogger(MissingImportStandIns.class);
    private static final String ERROR_PREFIX = "http://org.semanticweb.owlapi/error#";
    private static final long MAX_SCAN_BYTES = 50L * 1024 * 1024;
    private static final byte[] IMPORTS = "imports".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private MissingImportStandIns() {
    }

    public static boolean hasErrorPlaceholders(OWLOntology ontology) {
        return ontology.signature().anyMatch(e -> e.getIRI().toString().startsWith(ERROR_PREFIX));
    }

    public static OWLOntology load(OWLOntologyManager manager, Path file) throws OWLOntologyCreationException {
        Scan scan = null;
        try {
            if (Files.size(file) <= MAX_SCAN_BYTES && mentionsImports(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    scan = scan(in);
                }
            }
        } catch (Exception e) {
            log.debug("Import stand-in scan skipped for {}: {}", file.getFileName(), e.getMessage());
        }
        return loadWith(manager, scan, () -> manager.loadOntologyFromOntologyDocument(file.toFile()));
    }

    public static OWLOntology load(OWLOntologyManager manager, byte[] rdfXml) throws OWLOntologyCreationException {
        Scan scan = null;
        try {
            if (rdfXml.length <= MAX_SCAN_BYTES && indexOf(rdfXml, rdfXml.length, IMPORTS) >= 0) {
                scan = scan(new ByteArrayInputStream(rdfXml));
            }
        } catch (Exception e) {
            log.debug("Import stand-in scan skipped: {}", e.getMessage());
        }
        return loadWith(manager, scan, () -> manager.loadOntologyFromOntologyDocument(new ByteArrayInputStream(rdfXml)));
    }

    static boolean mentionsImports(Path file) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int carry = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer, carry, buffer.length - carry)) > 0) {
                int end = carry + read;
                if (indexOf(buffer, end, IMPORTS) >= 0) {
                    return true;
                }
                carry = Math.min(IMPORTS.length - 1, end);
                System.arraycopy(buffer, end - carry, buffer, 0, carry);
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle) {
        for (int i = 0; i <= length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    private interface Loader {
        OWLOntology load() throws OWLOntologyCreationException;
    }

    private static OWLOntology loadWith(OWLOntologyManager manager, Scan scan, Loader loader)
            throws OWLOntologyCreationException {
        OWLOntology standIn = null;
        Set<OWLAxiom> declarations = new LinkedHashSet<>();
        if (scan != null && !scan.imports.isEmpty()) {
            OWLDataFactory df = manager.getOWLDataFactory();
            for (String iri : scan.untyped) {
                declarations.add(df.getOWLDeclarationAxiom(df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(iri))));
            }
            for (String iri : scan.objectProps) {
                declarations.add(df.getOWLDeclarationAxiom(df.getOWLObjectProperty(org.semanticweb.owlapi.model.IRI.create(iri))));
            }
            for (String iri : scan.dataProps) {
                declarations.add(df.getOWLDeclarationAxiom(df.getOWLDataProperty(org.semanticweb.owlapi.model.IRI.create(iri))));
            }
        }
        if (!declarations.isEmpty()) {
            try {
                standIn = manager.createOntology(declarations,
                        org.semanticweb.owlapi.model.IRI.create(scan.imports.iterator().next()));
            } catch (OWLOntologyCreationException e) {
                log.debug("Import stand-in not created: {}", e.getMessage());
            }
        }
        OWLOntology ontology;
        try {
            ontology = loader.load();
        } catch (OWLOntologyCreationException | RuntimeException e) {
            if (standIn != null) {
                manager.removeOntology(standIn);
            }
            throw e;
        }
        if (standIn != null) {
            Set<OWLAxiom> used = new HashSet<>();
            for (OWLAxiom d : declarations) {
                if (d.signature().anyMatch(ontology::containsEntityInSignature)) {
                    used.add(d);
                }
            }
            manager.addAxioms(ontology, used);
            manager.removeOntology(standIn);
            log.info("Declared {} classes that only a skipped import described", used.size());
        }
        return ontology;
    }

    private record Scan(Set<String> imports, Set<String> untyped, Set<String> objectProps, Set<String> dataProps) {
    }

    private static Scan scan(InputStream in) throws IOException {
        Set<String> typed = new HashSet<>();
        Set<String> imports = new LinkedHashSet<>();
        Set<String> direct = new HashSet<>();
        Set<String> heads = new HashSet<>();
        Map<String, String> onProperty = new HashMap<>();
        Set<String> withOnClass = new HashSet<>();
        Set<String> withOnDataRange = new HashSet<>();
        Map<String, String> first = new HashMap<>();
        Map<String, String> rest = new HashMap<>();
        RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
        parser.setRDFHandler(new AbstractRDFHandler() {
            @Override
            public void handleStatement(Statement st) {
                IRI p = st.getPredicate();
                String s = st.getSubject().stringValue();
                var o = st.getObject();
                if (p.equals(RDF.TYPE)) {
                    typed.add(s);
                } else if (p.equals(RDF.FIRST)) {
                    if (o.isIRI()) {
                        first.put(s, o.stringValue());
                    }
                } else if (p.equals(RDF.REST)) {
                    rest.put(s, o.stringValue());
                } else if (p.equals(OWL.INTERSECTIONOF) || p.equals(OWL.UNIONOF)) {
                    heads.add(o.stringValue());
                } else if (p.equals(OWL.ONCLASS) || p.equals(OWL.COMPLEMENTOF)) {
                    if (p.equals(OWL.ONCLASS)) {
                        withOnClass.add(s);
                    }
                    if (o.isIRI()) {
                        direct.add(o.stringValue());
                    }
                } else if (p.equals(OWL.ONPROPERTY)) {
                    if (o.isIRI()) {
                        onProperty.put(s, o.stringValue());
                    }
                } else if (p.equals(OWL.ONDATARANGE)) {
                    withOnDataRange.add(s);
                } else if (p.equals(OWL.IMPORTS) && o.isIRI()) {
                    imports.add(o.stringValue());
                }
            }
        });
        parser.parse(in, "http://scan.invalid/");
        Set<String> candidates = new LinkedHashSet<>(direct);
        for (String head : heads) {
            Set<String> seen = new HashSet<>();
            String node = head;
            while (node != null && !RDF.NIL.stringValue().equals(node) && seen.add(node)) {
                String member = first.get(node);
                if (member != null) {
                    candidates.add(member);
                }
                node = rest.get(node);
            }
        }
        candidates.removeIf(c -> typed.contains(c) || c.startsWith("http://www.w3.org/"));
        Set<String> objectProps = new LinkedHashSet<>();
        Set<String> dataProps = new LinkedHashSet<>();
        onProperty.forEach((restriction, prop) -> {
            if (typed.contains(prop)) {
                return;
            }
            if (withOnClass.contains(restriction)) {
                objectProps.add(prop);
            } else if (withOnDataRange.contains(restriction)) {
                dataProps.add(prop);
            }
        });
        return new Scan(imports, candidates, objectProps, dataProps);
    }
}
