package self.research.ontology.owlEditor.service;

final class TopLevelClassSparql {

    static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    static final String NO_NAMED_PARENT = """
              FILTER NOT EXISTS {
                ?c rdfs:subClassOf ?p .
                FILTER(isIRI(?p) && ?p != <%s> && ?p != ?c)
              }""".formatted(THING);

    private TopLevelClassSparql() {}

    static String explicitRoots(String prefixes, int limit) {
        return prefixes + """
            SELECT ?c WHERE {
              ?c rdfs:subClassOf <%s> .
              FILTER(isIRI(?c))
              %s
            }
            ORDER BY ?c
            LIMIT %d
            """.formatted(THING, NO_NAMED_PARENT, Math.max(1, limit));
    }

    static String explicitRootCount(String prefixes) {
        return prefixes + """
            SELECT (COUNT(DISTINCT ?c) AS ?count) WHERE {
              ?c rdfs:subClassOf <%s> .
              FILTER(isIRI(?c))
              %s
            }
            """.formatted(THING, NO_NAMED_PARENT);
    }
}
