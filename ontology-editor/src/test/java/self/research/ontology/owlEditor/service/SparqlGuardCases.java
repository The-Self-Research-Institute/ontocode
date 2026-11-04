package self.research.ontology.owlEditor.service;

import java.util.List;

final class SparqlGuardCases {

    private SparqlGuardCases() {}

    static List<String> hostile() {
        return List.of(
            "SELECT * FROM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "SELECT * FROM NAMED <http://ontocode.org/project/projB> WHERE { GRAPH ?g { ?s ?p ?o } }",
            "SELECT * \\u0046ROM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "SELECT * fRoM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "select * from named <http://ontocode.org/project/projB> where { ?s ?p ?o }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/draft/projA/userB> { ?s ?p ?o } }",
            "SELECT * WHERE { { SELECT ?s WHERE { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } } } }",
            "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { GRAPH <http://ontocode.org/project/projB> { ?s ?p2 ?o2 } } }",
            "SELECT * WHERE { ?s ?p ?o FILTER NOT EXISTS { GRAPH <http://ontocode.org/project/projB> { ?s ?p2 ?o2 } } }",
            "SELECT * WHERE { ?s ?p ?o OPTIONAL { GRAPH <http://ontocode.org/project/projB> { ?s ?p2 ?o2 } } }",
            "SELECT * WHERE { { ?s ?p ?o } UNION { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } } }",
            "SELECT * WHERE { ?s ?p ?o MINUS { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projB> { ?s <http://e/p>+ ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projB> { ?s <http://e/p>* ?o } }",
            "BASE <http://ontocode.org/project/> SELECT * WHERE { GRAPH <projB> { ?s ?p ?o } }",
            "PREFIX g: <http://ontocode.org/project/> SELECT * WHERE { GRAPH g:projB { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projA/../projB> { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/proj%42> { ?s ?p ?o } }",
            "SELECT * WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }",
            "SELECT * WHERE { SERVICE SILENT <http://example.org/sparql> { ?s ?p ?o } }",
            "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { SERVICE <http://example.org/sparql> { ?s ?p ?o } } }",
            "SELECT * WHERE { { SELECT ?s WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } } } }",
            "SELECT (<java:java.lang.Math.abs>(1) AS ?x) WHERE { ?s ?p ?o }",
            "SELECT * WHERE { ?s ?p ?o FILTER(<http://example.org/fn/custom>(?o)) }",
            "SELECT * WHERE { ?s <java:com.example.Prop> ?o }",
            "SELECT * WHERE { ?s <http://jena.apache.org/ARQ/list#member> ?o }",
            "SELECT * WHERE { ?s <http://jena.apache.org/text#query> ?o }",
            "SELECT * WHERE { ?s <http://jena.hpl.hp.com/ARQ/property#bag> ?o }",
            "SELECT (<http://jena.apache.org/ARQ/function#sha1sum>(?o) AS ?x) WHERE { ?s ?p ?o }",
            "SELECT * WHERE { ?s ?p ?o } \n# harmless comment\n VALUES ?x { 1 } FROM <http://e/g>",
            "ASK FROM <http://ontocode.org/project/projB> { ?s ?p ?o }",
            "CONSTRUCT { ?s ?p ?o } FROM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "DESCRIBE <http://e/b1> FROM <http://ontocode.org/project/projB>",
            "INSERT DATA { <http://e/x> <http://e/p> 1 }",
            "DELETE WHERE { ?s ?p ?o }",
            "LOAD <http://example.org/data.ttl>",
            "CLEAR GRAPH <http://ontocode.org/project/projB>",
            "DROP ALL",
            "this is not sparql"
        );
    }
}
