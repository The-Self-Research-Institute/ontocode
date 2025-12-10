# Backend Implementation Requirements

The frontend now supports advanced OWL 2 features using Manchester Syntax and other editors.
However, the backend (`OntologyMutationService.java`) currently lacks the logic to parse these expressions and convert them into RDF triples (SPARQL INSERT).

## Missing Features in Backend

1.  **Manchester Syntax Parsing**:
    *   Need to integrate OWL API's `ManchesterOWLSyntaxParser`.
    *   Need to resolve entity names (labels) to IRIs during parsing.
    *   Need to convert parsed `OWLClassExpression` to RDF triples (Turtle/N-Triples) to insert via SPARQL.

2.  **Axiom Support**:
    *   `DisjointUnion`: Convert to `owl:disjointUnionOf` list.
    *   `PropertyChain`: Convert to `owl:propertyChainAxiom` list.
    *   `HasKey`: Convert to `owl:hasKey` list.
    *   `SameIndividual`/`DifferentIndividuals`: Convert to `owl:sameAs`/`owl:differentFrom`.

3.  **Service Update**:
    *   Update `OntologyMutationService.java` to handle `addAxiom` operation type.
    *   Implement `ExpressionParserService` or similar to handle the parsing logic.

## Next Steps

1.  Add `owlapi-distribution` dependency (already present).
2.  Create `ManchesterSyntaxParserService` in Java.
3.  Implement `addAxiom` logic in `OntologyMutationService`.
