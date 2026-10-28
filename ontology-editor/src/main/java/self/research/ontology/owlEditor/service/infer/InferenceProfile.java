package self.research.ontology.owlEditor.service.infer;

import java.util.List;

public final class InferenceProfile {

    public static final String VERSION = "v1";
    public static final String REASONER = "HermiT";
    public static final String REASONER_VERSION = "HermiT 1.4.5.519";
    public static final String REASONING_CONFIG = "no-unique-name-assumption;hermit-default;precompute-v1";
    public static final String INFERRED_NOTE = "SPARQL over a materialized RDF view; not full OWL 2 DL entailment.";

    public static final int MAX_GROUP_SIZE = 10;
    public static final int MAX_EQUALITY_TRIPLES = 100_000;
    public static final int MAX_ASSERTED_TRIPLES = 200_000;
    public static final long TIME_LIMIT_MS = 60_000L;
    public static final int CACHE_MAX_SNAPSHOTS = 2;
    public static final long CACHE_TTL_MS = 30L * 60L * 1000L;
    public static final long CACHE_MAX_TRIPLES = 600_000L;
    public static final long BUSY_WAIT_MS = 5_000L;
    public static final int RULE_MAX_ROUNDS = 8;
    public static final int RULE_MAX_DERIVED = 50_000;

    public static final List<String> LAYERS = List.of("owl", "equality", "rules");

    public static final List<String> INCLUDED = List.of(
            "rdfs:subClassOf closure among named classes",
            "owl:equivalentClass among named classes",
            "rdf:type for named individuals under every entailed named class",
            "object property assertions between named individuals, including inverse, symmetric, transitive and sub-property entailments",
            "data property assertions for named individuals",
            "owl:sameAs groups of named individuals, as all ordered pairs without reflexive triples",
            "owl:differentFrom only when stated, repeated across sameAs groups, never from different IRIs",
            "rdfs:subPropertyOf closure and owl:equivalentProperty among named object and data properties",
            "class assertions and object and data property facts under every name of a sameAs group, as subject and as object, originals kept",
            "class and property assertions concluded by enabled swrl:Imp rules");

    public static final List<String> EXCLUDED = List.of(
            "anonymous class expressions and anonymous individuals",
            "annotation assertions",
            "owl:Thing and owl:Nothing subclass or type triples",
            "owl:differentFrom between individuals that merely have different IRIs, or that follow only from disjoint classes",
            "rule conclusions of sameAs or differentFrom (the rule loop does not pass them on)",
            "entailments about property chains, keys and cardinalities beyond what the reasoner returns as named facts",
            "imported ontologies that are not stored in the project graphs");

    private InferenceProfile() {
    }

    public static String limitsFingerprint() {
        return "group=" + MAX_GROUP_SIZE + ";eq=" + MAX_EQUALITY_TRIPLES + ";asserted=" + MAX_ASSERTED_TRIPLES
                + ";timeMs=" + TIME_LIMIT_MS + ";rounds=" + RULE_MAX_ROUNDS + ";derived=" + RULE_MAX_DERIVED;
    }
}
