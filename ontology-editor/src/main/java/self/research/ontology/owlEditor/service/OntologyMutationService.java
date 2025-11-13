package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.dto.OntologyDtos.OntologyMetadataDto;

import java.util.List;

@Service
public class OntologyMutationService {

    private final SparqlUpdateService update;
    private final OntologySparqlService query;

    public OntologyMutationService(SparqlUpdateService update, OntologySparqlService query) {
        this.update = update;
        this.query = query;
    }

    // ========== HELPERS ==========

    private Mono<String> baseIri(String projectId) {
        return query.getMetadata(projectId)
                .map(OntologyMetadataDto::getOntologyIRI)
                .defaultIfEmpty("http://example.com/ont")
                .map(iri -> {
                    if (!iri.endsWith("#") && !iri.endsWith("/")) return iri + "#";
                    return iri;
                });
    }

    private String iriFromLabel(String base, String label) {
        String slug = label.trim().replaceAll("\\s+", "_");
        return base + slug;
    }

    // ========== CLASSES ==========

    public Mono<Void> createClass(String projectId, String iri, String label, String parentIri) {
        return baseIri(projectId).flatMap(base -> {
            String cls = (iri != null && !iri.isBlank()) ? iri : iriFromLabel(base, label);
            String g = update.graph(projectId);
            
            StringBuilder sb = new StringBuilder();
            sb.append("PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>\n")
              .append("PREFIX owl:<http://www.w3.org/2002/07/owl#>\n")
              .append("INSERT DATA { GRAPH <").append(g).append("> {\n")
              .append("  <").append(cls).append("> a owl:Class .\n");
            
            if (StringUtils.isNotBlank(label)) {
                sb.append("  <").append(cls).append("> rdfs:label ")
                  .append(SparqlUpdateService.lit(label)).append(" .\n");
            }
            if (StringUtils.isNotBlank(parentIri)) {
                sb.append("  <").append(cls).append("> rdfs:subClassOf <")
                  .append(parentIri).append("> .\n");
            }
            sb.append("} }");
            
            return update.executeUpdate(sb.toString());
        });
    }

    public Mono<Void> updateClassLabel(String projectId, String iri, String newLabel) {
        String g = update.graph(projectId);
        String upd = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            WITH <%s>
            DELETE { <%s> rdfs:label ?l . }
            INSERT { <%s> rdfs:label %s . }
            WHERE  { OPTIONAL { <%s> rdfs:label ?l } }
        """.formatted(g, iri, iri, SparqlUpdateService.lit(newLabel), iri);
        return update.executeUpdate(upd);
    }

    public Mono<Void> setClassParent(String projectId, String iri, String parentIri) {
        String g = update.graph(projectId);
        String upd = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            WITH <%s>
            DELETE { <%s> rdfs:subClassOf ?p . }
            INSERT { <%s> rdfs:subClassOf <%s> . }
            WHERE  { OPTIONAL { <%s> rdfs:subClassOf ?p } }
        """.formatted(g, iri, iri, parentIri, iri);
        return update.executeUpdate(upd);
    }

    public Mono<Boolean> canDeleteClass(String projectId, String iri) {
        String g = update.graph(projectId);
        String q = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            SELECT 
              (EXISTS { GRAPH <%s> { ?child rdfs:subClassOf <%s> FILTER(?child != <%s>) } } AS ?hasChildren)
              (EXISTS { GRAPH <%s> { ?ind a ?t . FILTER(?t = <%s>) } } AS ?hasInstances)
            WHERE {}
        """.formatted(g, iri, iri, g, iri);
        
        return query.executeSparqlQuery(q).map(json -> {
            JsonNode b = json.path("results").path("bindings");
            if (b.size() == 0) return true;
            JsonNode row = b.get(0);
            boolean hasChildren = row.path("hasChildren").path("value").asBoolean(false);
            boolean hasInstances = row.path("hasInstances").path("value").asBoolean(false);
            return !(hasChildren || hasInstances);
        });
    }

    public Mono<Void> deleteClass(String projectId, String iri) {
        String g = update.graph(projectId);
        String upd = """
            WITH <%s>
            DELETE { <%s> ?p ?o . ?s ?p2 <%s> . }
            WHERE  { { <%s> ?p ?o } UNION { ?s ?p2 <%s> } }
        """.formatted(g, iri, iri, iri, iri);
        return update.executeUpdate(upd);
    }

    // ========== PROPERTIES ==========

    public Mono<Void> createProperty(String projectId, String type, String iri, String label,
                                     List<String> domains, List<String> ranges) {
        String pType = "ObjectProperty".equals(type) ? "owl:ObjectProperty" : "owl:DatatypeProperty";
        String g = update.graph(projectId);
        
        StringBuilder sb = new StringBuilder("""
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            INSERT DATA { GRAPH <%s> {
        """.formatted(g));
        
        sb.append("  <").append(iri).append("> a ").append(pType).append(" .\n");
        
        if (StringUtils.isNotBlank(label)) {
            sb.append("  <").append(iri).append("> rdfs:label ")
              .append(SparqlUpdateService.lit(label)).append(" .\n");
        }
        if (domains != null) {
            domains.forEach(d -> sb.append("  <").append(iri).append("> rdfs:domain <")
                                   .append(d).append("> .\n"));
        }
        if (ranges != null) {
            ranges.forEach(r -> sb.append("  <").append(iri).append("> rdfs:range <")
                                  .append(r).append("> .\n"));
        }
        sb.append("}}");
        
        return update.executeUpdate(sb.toString());
    }

    public Mono<Void> updatePropertyLabel(String projectId, String iri, String label) {
        String g = update.graph(projectId);
        String upd = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            WITH <%s>
            DELETE { <%s> rdfs:label ?l . }
            INSERT { <%s> rdfs:label %s . }
            WHERE  { OPTIONAL { <%s> rdfs:label ?l } }
        """.formatted(g, iri, iri, SparqlUpdateService.lit(label), iri);
        return update.executeUpdate(upd);
    }

    public Mono<Void> replaceDomains(String projectId, String iri, List<String> newDomains) {
        String g = update.graph(projectId);
        StringBuilder ins = new StringBuilder();
        if (newDomains != null) {
            newDomains.forEach(d -> ins.append("  <").append(iri).append("> rdfs:domain <")
                                      .append(d).append("> .\n"));
        }
        String upd = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            WITH <%s>
            DELETE { <%s> rdfs:domain ?d . }
            INSERT { %s }
            WHERE  { OPTIONAL { <%s> rdfs:domain ?d } }
        """.formatted(g, iri, ins, iri);
        return update.executeUpdate(upd);
    }

    public Mono<Void> replaceRanges(String projectId, String iri, List<String> newRanges) {
        String g = update.graph(projectId);
        StringBuilder ins = new StringBuilder();
        if (newRanges != null) {
            newRanges.forEach(r -> ins.append("  <").append(iri).append("> rdfs:range <")
                                     .append(r).append("> .\n"));
        }
        String upd = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            WITH <%s>
            DELETE { <%s> rdfs:range ?r . }
            INSERT { %s }
            WHERE  { OPTIONAL { <%s> rdfs:range ?r } }
        """.formatted(g, iri, ins, iri);
        return update.executeUpdate(upd);
    }

    public Mono<Void> deleteProperty(String projectId, String iri) {
        String g = update.graph(projectId);
        String upd = """
            WITH <%s>
            DELETE { <%s> ?p ?o . ?s ?p2 <%s> . }
            WHERE  { { <%s> ?p ?o } UNION { ?s ?p2 <%s> } }
        """.formatted(g, iri, iri, iri, iri);
        return update.executeUpdate(upd);
    }

    // ========== INDIVIDUALS ==========

    public Mono<Void> createIndividual(String projectId, String iri, String label, List<String> types) {
        String g = update.graph(projectId);
        StringBuilder sb = new StringBuilder("""
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            INSERT DATA { GRAPH <%s> {
        """.formatted(g));
        
        sb.append("  <").append(iri).append("> a owl:NamedIndividual .\n");
        
        if (StringUtils.isNotBlank(label)) {
            sb.append("  <").append(iri).append("> rdfs:label ")
              .append(SparqlUpdateService.lit(label)).append(" .\n");
        }
        if (types != null) {
            types.forEach(t -> sb.append("  <").append(iri).append("> a <")
                                 .append(t).append("> .\n"));
        }
        sb.append("}}");
        
        return update.executeUpdate(sb.toString());
    }

    public Mono<Void> updateIndividualLabel(String projectId, String iri, String label) {
        String g = update.graph(projectId);
        String upd = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            WITH <%s>
            DELETE { <%s> rdfs:label ?l . }
            INSERT { <%s> rdfs:label %s . }
            WHERE  { OPTIONAL { <%s> rdfs:label ?l } }
        """.formatted(g, iri, iri, SparqlUpdateService.lit(label), iri);
        return update.executeUpdate(upd);
    }

    public Mono<Void> setIndividualTypes(String projectId, String iri, List<String> newTypes) {
        String g = update.graph(projectId);
        StringBuilder ins = new StringBuilder();
        ins.append("  <").append(iri).append("> a <http://www.w3.org/2002/07/owl#NamedIndividual> .\n");
        if (newTypes != null) {
            newTypes.forEach(t -> ins.append("  <").append(iri).append("> a <")
                                    .append(t).append("> .\n"));
        }
        String upd = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            WITH <%s>
            DELETE { <%s> a ?t . }
            INSERT { %s }
            WHERE  { <%s> a ?t . }
        """.formatted(g, iri, ins, iri);
        return update.executeUpdate(upd);
    }

    public Mono<Void> deleteIndividual(String projectId, String iri) {
        String g = update.graph(projectId);
        String upd = """
            WITH <%s>
            DELETE { <%s> ?p ?o . ?s ?p2 <%s> . }
            WHERE  { { <%s> ?p ?o } UNION { ?s ?p2 <%s> } }
        """.formatted(g, iri, iri, iri, iri);
        return update.executeUpdate(upd);
    }

    // ========== PROPERTY ASSERTIONS ==========

    public Mono<Void> addPropertyAssertion(String projectId, String subjectIri,
                                           String propertyIri, String objectIri) {
        String g = update.graph(projectId);
        String upd = """
            INSERT DATA { GRAPH <%s> { <%s> <%s> <%s> . } }
        """.formatted(g, subjectIri, propertyIri, objectIri);
        return update.executeUpdate(upd);
    }

    public Mono<Void> removePropertyAssertion(String projectId, String subjectIri,
                                              String propertyIri, String objectIri) {
        String g = update.graph(projectId);
        String upd = """
            WITH <%s>
            DELETE { <%s> <%s> <%s> . }
            WHERE  { <%s> <%s> <%s> . }
        """.formatted(g, subjectIri, propertyIri, objectIri, subjectIri, propertyIri, objectIri);
        return update.executeUpdate(upd);
    }

    public Mono<JsonNode> getIndividualAssertions(String projectId, String individualIri) {
        String g = update.graph(projectId);
        String q = """
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            PREFIX rdf:<http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?property ?value (SAMPLE(?propLabel) AS ?propertyLabel) (SAMPLE(?valLabel) AS ?valueLabel)
            WHERE { 
              GRAPH <%s> {
                <%s> ?property ?value .
                FILTER(?property != rdf:type)
                FILTER(?property != rdfs:label)
                OPTIONAL { ?property rdfs:label ?propLabel }
                OPTIONAL { ?value rdfs:label ?valLabel }
              }
            } 
            GROUP BY ?property ?value
        """.formatted(g, individualIri);
        return query.executeSparqlQuery(q);
    }

    // ========== ANNOTATIONS ==========

    public Mono<Void> addAnnotation(String projectId, String subjectIri, String propIri,
                                    String value, String lang, String datatypeIri) {
        String g = update.graph(projectId);
        String lit = (datatypeIri != null && !datatypeIri.isBlank())
                ? SparqlUpdateService.lit(value) + "^^<" + datatypeIri + ">"
                : (lang != null && !lang.isBlank())
                    ? SparqlUpdateService.lit(value) + "@" + lang
                    : SparqlUpdateService.lit(value);
        
        String upd = """
            INSERT DATA { GRAPH <%s> { <%s> <%s> %s . } }
        """.formatted(g, subjectIri, propIri, lit);
        return update.executeUpdate(upd);
    }

    public Mono<Void> deleteAnnotation(String projectId, String subjectIri, String propIri, String value) {
        String g = update.graph(projectId);
        String upd = """
            WITH <%s>
            DELETE { <%s> <%s> %s . }
            WHERE  { <%s> <%s> %s . }
        """.formatted(g, subjectIri, propIri, SparqlUpdateService.lit(value), 
                     subjectIri, propIri, SparqlUpdateService.lit(value));
        return update.executeUpdate(upd);
    }

    // ========== AXIOMS ==========

    public Mono<Void> addEquivalentClasses(String projectId, String class1, String class2) {
        String g = update.graph(projectId);
        String upd = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            INSERT DATA { GRAPH <%s> { <%s> owl:equivalentClass <%s> . } }
        """.formatted(g, class1, class2);
        return update.executeUpdate(upd);
    }

    public Mono<Void> addDisjointClasses(String projectId, List<String> classes) {
        if (classes == null || classes.size() < 2) {
            return Mono.error(new IllegalArgumentException("Need at least 2 classes"));
        }
        
        String g = update.graph(projectId);
        StringBuilder sb = new StringBuilder();
        sb.append("PREFIX owl:<http://www.w3.org/2002/07/owl#>\n");
        sb.append("INSERT DATA { GRAPH <").append(g).append("> {\n");
        sb.append("  [] a owl:AllDisjointClasses ;\n");
        sb.append("     owl:members ( ");
        classes.forEach(cls -> sb.append("<").append(cls).append("> "));
        sb.append(") .\n}}");
        
        return update.executeUpdate(sb.toString());
    }

    public Mono<Void> addEquivalentProperties(String projectId, String prop1, String prop2) {
        String g = update.graph(projectId);
        String upd = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            INSERT DATA { GRAPH <%s> { <%s> owl:equivalentProperty <%s> . } }
        """.formatted(g, prop1, prop2);
        return update.executeUpdate(upd);
    }

    // ========== METADATA ==========

    public Mono<Void> updateOntologyMetadata(String projectId, String versionIri,
                                            List<String> imports, Map<String,String> annotations) {
        return query.getMetadata(projectId).flatMap(meta -> {
            String ontologyIri = meta.getOntologyIRI();
            if (ontologyIri == null || ontologyIri.isEmpty()) {
                return Mono.error(new RuntimeException("Ontology IRI not found"));
            }
            
            String g = update.graph(projectId);
            StringBuilder sb = new StringBuilder();
            sb.append("PREFIX owl:<http://www.w3.org/2002/07/owl#>\n");
            sb.append("PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>\n");
            
            // Delete old metadata
            sb.append("WITH <").append(g).append(">\n");
            sb.append("DELETE {\n");
            sb.append("  <").append(ontologyIri).append("> owl:versionIRI ?oldVersion .\n");
            sb.append("  <").append(ontologyIri).append("> owl:imports ?oldImport .\n");
            sb.append("} WHERE {\n");
            sb.append("  OPTIONAL { <").append(ontologyIri).append("> owl:versionIRI ?oldVersion }\n");
            sb.append("  OPTIONAL { <").append(ontologyIri).append("> owl:imports ?oldImport }\n");
            sb.append("};\n\n");
            
            // Insert new metadata
            sb.append("INSERT DATA { GRAPH <").append(g).append("> {\n");
            if (versionIri != null && !versionIri.isBlank()) {
                sb.append("  <").append(ontologyIri).append("> owl:versionIRI <")
                  .append(versionIri).append("> .\n");
            }
            if (imports != null) {
                imports.forEach(imp ->
                    sb.append("  <").append(ontologyIri).append("> owl:imports <")
                      .append(imp).append("> .\n")
                );
            }
            if (annotations != null) {
                annotations.forEach((prop, value) ->
                    sb.append("  <").append(ontologyIri).append("> <").append(prop).append("> ")
                      .append(SparqlUpdateService.lit(value)).append(" .\n")
                );
            }
            sb.append("}}");
            
            return update.executeUpdate(sb.toString());
        });
    }
}