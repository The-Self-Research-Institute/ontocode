package self.research.ontology.owlEditor.service;

final class ListAxiomCleanupSparql {

    private ListAxiomCleanupSparql() {
    }

    static String deleteReferencing(String iri) {
        return """
            DELETE { ?axiom ?ap ?ao . ?cell ?cp ?co . }
            WHERE {
              ?member rdf:first <%1$s> .
              ?head rdf:rest* ?member .
              ?axiom owl:members|owl:distinctMembers ?head .
              FILTER(isBlank(?axiom))
              ?axiom ?ap ?ao .
              ?head rdf:rest* ?cell .
              FILTER(isBlank(?cell))
              ?cell ?cp ?co .
            };
            DELETE { ?owner ?lp ?head . ?cell ?cp ?co . }
            WHERE {
              ?member rdf:first <%1$s> .
              ?head rdf:rest* ?member .
              ?owner ?lp ?head .
              FILTER(?lp IN (owl:disjointUnionOf, owl:hasKey, owl:propertyChainAxiom))
              ?head rdf:rest* ?cell .
              FILTER(isBlank(?cell))
              ?cell ?cp ?co .
            }""".formatted(iri);
    }
}
