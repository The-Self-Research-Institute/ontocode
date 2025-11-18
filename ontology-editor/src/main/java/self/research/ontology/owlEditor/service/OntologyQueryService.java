package self.research.ontology.owlEditor.service;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.dto.AnnotationPropertyDto;
import self.research.ontology.owlEditor.dto.DatatypeDto;
import self.research.ontology.owlEditor.dto.IndividualDto;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.dto.PropertyDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OntologyQueryService {

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        """;

    private final Tdb2DatasetService datasetService;

    public OntologyQueryService(Tdb2DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit) {
        String query = PREFIXES + """
            SELECT ?c ?label (EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c) } AS ?hasChildren)
            WHERE {
              ?c a owl:Class .
              FILTER NOT EXISTS { ?c rdfs:subClassOf ?super FILTER(?super != ?c) }
              OPTIONAL { ?c rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            LIMIT %d
            """.formatted(Math.max(1, limit));
        System.out.println("=== TOP LEVEL CLASSES QUERY ===");
        System.out.println(query);
        List<OntologyDto.TreeNode> result = mapTreeNodes(projectId, query, null);
        System.out.println("=== QUERY RETURNED " + result.size() + " RESULTS ===");
        return result;
    }

    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        String query = PREFIXES + """
            SELECT ?child ?label (EXISTS { ?gchild rdfs:subClassOf ?child . FILTER(?gchild != ?child) } AS ?hasChildren)
            WHERE {
              ?child rdfs:subClassOf <%s> .
              OPTIONAL { ?child rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?child))
            LIMIT %d OFFSET %d
            """.formatted(parentIri, Math.max(1, limit), Math.max(0, offset));
        return mapTreeNodes(projectId, query, parentIri);
    }

    public List<PropertyDto> properties(String projectId, String type, int limit, int offset) {
        String filter = switch (normalize(type)) {
            case "object" -> "FILTER(?kind = owl:ObjectProperty)";
            case "data" -> "FILTER(?kind = owl:DatatypeProperty)";
            default -> "";
        };

        String query = PREFIXES + """
            SELECT ?prop (SAMPLE(?lbl) AS ?label) ?kind
                   (GROUP_CONCAT(DISTINCT ?domain; SEPARATOR="|") AS ?domains)
                   (GROUP_CONCAT(DISTINCT ?range; SEPARATOR="|") AS ?ranges)
            WHERE {
              ?prop a ?kind .
              VALUES ?kind { owl:ObjectProperty owl:DatatypeProperty }
              %s
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:domain ?domain . FILTER(isIRI(?domain)) }
              OPTIONAL { ?prop rdfs:range ?range . FILTER(isIRI(?range)) }
            }
            GROUP BY ?prop ?kind
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            LIMIT %d OFFSET %d
            """.formatted(filter, Math.max(1, limit), Math.max(0, offset));

        System.out.println("=== PROPERTIES QUERY ===");
        System.out.println(query);
        ResultSet rs = datasetService.execSelect(projectId, query);
        List<PropertyDto> results = new ArrayList<>();
        int count = 0;
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            count++;
            String iri = resource(sol, "prop");
            if (iri == null) {
                continue;
            }
            PropertyDto dto = new PropertyDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            dto.setType(localName(resource(sol, "kind")));
            dto.setDomains(splitPipe(literal(sol, "domains")));
            dto.setRanges(splitPipe(literal(sol, "ranges")));
            results.add(dto);
        }
        System.out.println("=== PROPERTIES QUERY RETURNED " + count + " ROWS, " + results.size() + " PROPERTIES ===");
        return results;
    }

    public List<IndividualDto> individuals(String projectId, int limit, int offset) {
        String query = PREFIXES + """
            SELECT ?ind (SAMPLE(?lbl) AS ?label)
                   (GROUP_CONCAT(DISTINCT ?type; SEPARATOR="|") AS ?types)
            WHERE {
              ?ind a owl:NamedIndividual .
              OPTIONAL { ?ind rdfs:label ?lbl }
              OPTIONAL {
                ?ind a ?type .
                FILTER(?type != owl:NamedIndividual && isIRI(?type))
              }
            }
            GROUP BY ?ind
            ORDER BY COALESCE(LCASE(?label), STR(?ind))
            LIMIT %d OFFSET %d
            """.formatted(Math.max(1, limit), Math.max(0, offset));

        ResultSet rs = datasetService.execSelect(projectId, query);
        List<IndividualDto> individuals = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = resource(sol, "ind");
            if (iri == null) {
                continue;
            }
            IndividualDto dto = new IndividualDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            dto.setTypes(splitPipe(literal(sol, "types")));
            individuals.add(dto);
        }
        return individuals;
    }

    public long individualCount(String projectId) {
        String query = PREFIXES + """
            SELECT (COUNT(DISTINCT ?ind) AS ?count)
            WHERE { ?ind a owl:NamedIndividual . }
            """;
        ResultSet rs = datasetService.execSelect(projectId, query);
        if (rs.hasNext()) {
            QuerySolution sol = rs.next();
            return sol.contains("count") ? sol.getLiteral("count").getLong() : 0L;
        }
        return 0L;
    }

    public List<AnnotationPropertyDto> annotationProperties(String projectId, int limit, int offset) {
        String query = PREFIXES + """
            SELECT DISTINCT ?prop (SAMPLE(?lbl) AS ?label)
            WHERE {
              ?prop a owl:AnnotationProperty .
              FILTER(!isBlank(?prop))
              OPTIONAL { ?prop rdfs:label ?lbl }
            }
            GROUP BY ?prop
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            LIMIT %d OFFSET %d
            """.formatted(Math.max(1, limit), Math.max(0, offset));

        System.out.println("=== ANNOTATION PROPERTIES QUERY ===");
        System.out.println(query);
        ResultSet rs = datasetService.execSelect(projectId, query);
        List<AnnotationPropertyDto> props = new ArrayList<>();
        int count = 0;
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            count++;
            String iri = resource(sol, "prop");
            if (iri == null) {
                continue;
            }
            AnnotationPropertyDto dto = new AnnotationPropertyDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            props.add(dto);
        }
        System.out.println("=== ANNOTATION PROPERTIES QUERY RETURNED " + count + " ROWS, " + props.size() + " PROPERTIES ===");
        return props;
    }

    public List<Map<String, String>> annotationPropertyUsage(String projectId, String propertyIri) {
        String query = PREFIXES + """
            SELECT ?subject ?subjectLabel ?value
            WHERE {
              ?subject <%s> ?value .
              OPTIONAL { ?subject rdfs:label ?subjectLabel }
              OPTIONAL { ?subject a ?type }
            }
            ORDER BY STR(?subject)
            LIMIT 1000
            """.formatted(propertyIri);

        ResultSet rs = datasetService.execSelect(projectId, query);
        List<Map<String, String>> usages = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String subjectIri = resource(sol, "subject");
            if (subjectIri == null) {
                continue;
            }
            String subjectLabel = literal(sol, "subjectLabel");
            String value = sol.contains("value") ? sol.get("value").toString() : "";
            
            usages.add(Map.of(
                "subject", subjectIri,
                "subjectLabel", subjectLabel.isBlank() ? localName(subjectIri) : subjectLabel,
                "value", value
            ));
        }
        return usages;
    }

    public List<DatatypeDto> datatypes(String projectId, int limit, int offset) {
        // First get datatypes declared in the ontology
        String query = PREFIXES + """
            SELECT DISTINCT ?dt
            WHERE {
              ?dt a rdfs:Datatype .
              FILTER(isIRI(?dt))
            }
            ORDER BY STR(?dt)
            """;

        ResultSet rs = datasetService.execSelect(projectId, query);
        Set<String> datatypes = new LinkedHashSet<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = resource(sol, "dt");
            if (iri != null) {
                datatypes.add(iri);
            }
        }

        // Add standard OWL 2 datatypes
        datatypes.add("http://www.w3.org/2002/07/owl#rational");
        datatypes.add("http://www.w3.org/2002/07/owl#real");
        
        // Add standard RDF datatypes
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString");
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral");
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral");
        
        // Add standard RDFS datatypes
        datatypes.add("http://www.w3.org/2000/01/rdf-schema#Literal");
        
        // Add comprehensive XSD datatypes
        String[] xsdTypes = {
            "anyURI", "base64Binary", "boolean", "byte", "date", "dateTime", "dateTimeStamp",
            "decimal", "double", "float", "hexBinary", "int", "integer", "language",
            "long", "Name", "NCName", "negativeInteger", "NMTOKEN", "nonNegativeInteger",
            "nonPositiveInteger", "normalizedString", "positiveInteger", "short", "string",
            "time", "token", "unsignedByte", "unsignedInt", "unsignedLong", "unsignedShort"
        };
        for (String type : xsdTypes) {
            datatypes.add("http://www.w3.org/2001/XMLSchema#" + type);
        }

        // Convert to DTOs with pagination
        List<DatatypeDto> result = new ArrayList<>();
        int index = 0;
        for (String iri : datatypes) {
            if (index >= offset && result.size() < limit) {
                DatatypeDto dto = new DatatypeDto();
                dto.setId(iri);
                dto.setIri(iri);
                dto.setLabel(localName(iri));
                result.add(dto);
            }
            index++;
            if (result.size() >= limit) break;
        }
        
        return result;
    }

    private List<OntologyDto.TreeNode> mapTreeNodes(String projectId, String query, String parentIri) {
        ResultSet rs = datasetService.execSelect(projectId, query);
        List<OntologyDto.TreeNode> nodes = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = resource(sol, parentIri == null ? "c" : "child");
            if (iri == null) continue;
            OntologyDto.TreeNode node = new OntologyDto.TreeNode();
            node.setId(iri);
            String label = literal(sol, parentIri == null ? "label" : "label");
            node.setLabel(label.isBlank() ? localName(iri) : label);
            node.setHasChildren(sol.contains("hasChildren") && sol.get("hasChildren").isLiteral()
                    && sol.getLiteral("hasChildren").getBoolean());
            node.setParent(parentIri);
            nodes.add(node);
        }
        return nodes;
    }

    private String resource(QuerySolution sol, String var) {
        if (sol.contains(var)) {
            RDFNode node = sol.get(var);
            if (node != null && node.isResource()) {
                return node.asResource().getURI();
            }
        }
        return null;
    }

    private String literal(QuerySolution sol, String var) {
        if (sol.contains(var)) {
            RDFNode node = sol.get(var);
            if (node != null && node.isLiteral()) {
                return node.asLiteral().getString();
            }
        }
        return "";
    }

    private List<String> splitPipe(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String part : value.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    private String localName(String iri) {
        if (iri == null || iri.isBlank()) {
            return "";
        }
        int idx = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return idx >= 0 && idx < iri.length() - 1 ? iri.substring(idx + 1) : iri;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> debugInfo(String projectId) {
        // Count all triples
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        ResultSet rs1 = datasetService.execSelect(projectId, countQuery);
        long totalTriples = 0;
        if (rs1.hasNext()) {
            QuerySolution sol = rs1.next();
            if (sol.contains("count")) {
                totalTriples = sol.getLiteral("count").getLong();
            }
        }

        // Count OWL classes
        String classQuery = PREFIXES + "SELECT (COUNT(DISTINCT ?c) AS ?count) WHERE { ?c a owl:Class }";
        ResultSet rs2 = datasetService.execSelect(projectId, classQuery);
        long classCount = 0;
        if (rs2.hasNext()) {
            QuerySolution sol = rs2.next();
            if (sol.contains("count")) {
                classCount = sol.getLiteral("count").getLong();
            }
        }

        // Count annotation properties
        String annQuery = PREFIXES + "SELECT (COUNT(DISTINCT ?p) AS ?count) WHERE { ?p a owl:AnnotationProperty }";
        ResultSet rs3 = datasetService.execSelect(projectId, annQuery);
        long annCount = 0;
        if (rs3.hasNext()) {
            QuerySolution sol = rs3.next();
            if (sol.contains("count")) {
                annCount = sol.getLiteral("count").getLong();
            }
        }

        // Sample some triples
        String sampleQuery = "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10";
        ResultSet rs4 = datasetService.execSelect(projectId, sampleQuery);
        List<String> sampleTriples = new ArrayList<>();
        while (rs4.hasNext()) {
            QuerySolution sol = rs4.next();
            sampleTriples.add(sol.get("s") + " " + sol.get("p") + " " + sol.get("o"));
        }

        return Map.of(
            "totalTriples", totalTriples,
            "classCount", classCount,
            "annotationPropertyCount", annCount,
            "sampleTriples", sampleTriples
        );
    }

    public List<Map<String, String>> classUsage(String projectId, String classIri) {
        List<Map<String, String>> usages = new ArrayList<>();
        
        // 1. Find subclasses
        String subclassQuery = PREFIXES + """
            SELECT DISTINCT ?subclass ?label WHERE {
              ?subclass rdfs:subClassOf <%s> .
              OPTIONAL { ?subclass rdfs:label ?label }
            }
            """.formatted(classIri);
        ResultSet subclasses = datasetService.execSelect(projectId, subclassQuery);
        while (subclasses.hasNext()) {
            QuerySolution sol = subclasses.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "subclass");
            usage.put("subject", sol.getResource("subclass").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("subclass").getLocalName());
            usage.put("context", "SubClassOf " + classIri);
            usages.add(usage);
        }
        
        // 2. Find individuals of this class
        String instanceQuery = PREFIXES + """
            SELECT DISTINCT ?individual ?label WHERE {
              ?individual a <%s> .
              OPTIONAL { ?individual rdfs:label ?label }
            }
            """.formatted(classIri);
        ResultSet instances = datasetService.execSelect(projectId, instanceQuery);
        while (instances.hasNext()) {
            QuerySolution sol = instances.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "instance");
            usage.put("subject", sol.getResource("individual").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("individual").getLocalName());
            usage.put("context", "Individual of " + classIri);
            usages.add(usage);
        }
        
        // 3. Find disjoint classes
        String disjointQuery = PREFIXES + """
            SELECT DISTINCT ?disjoint ?label WHERE {
              {
                <%s> owl:disjointWith ?disjoint .
              } UNION {
                ?disjoint owl:disjointWith <%s> .
              }
              OPTIONAL { ?disjoint rdfs:label ?label }
            }
            """.formatted(classIri, classIri);
        ResultSet disjoints = datasetService.execSelect(projectId, disjointQuery);
        while (disjoints.hasNext()) {
            QuerySolution sol = disjoints.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "disjoint");
            usage.put("subject", sol.getResource("disjoint").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("disjoint").getLocalName());
            usage.put("context", "DisjointWith");
            usages.add(usage);
        }
        
        // 4. Find named superclasses
        String superclassQuery = PREFIXES + """
            SELECT DISTINCT ?superclass ?label WHERE {
              <%s> rdfs:subClassOf ?superclass .
              FILTER(isIRI(?superclass) && ?superclass != owl:Thing)
              OPTIONAL { ?superclass rdfs:label ?label }
            }
            """.formatted(classIri);
        ResultSet superclasses = datasetService.execSelect(projectId, superclassQuery);
        while (superclasses.hasNext()) {
            QuerySolution sol = superclasses.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "superclass");
            usage.put("subject", sol.getResource("superclass").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("superclass").getLocalName());
            usage.put("context", "SuperClassOf");
            usages.add(usage);
        }
        
        // 5. Find properties with this class as domain
        String domainQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:domain <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(classIri);
        ResultSet domains = datasetService.execSelect(projectId, domainQuery);
        while (domains.hasNext()) {
            QuerySolution sol = domains.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "domain");
            usage.put("subject", sol.getResource("prop").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("prop").getLocalName());
            usage.put("context", "Domain of property");
            usages.add(usage);
        }
        
        // 6. Find properties with this class as range
        String rangeQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:range <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(classIri);
        ResultSet ranges = datasetService.execSelect(projectId, rangeQuery);
        while (ranges.hasNext()) {
            QuerySolution sol = ranges.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "range");
            usage.put("subject", sol.getResource("prop").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("prop").getLocalName());
            usage.put("context", "Range of property");
            usages.add(usage);
        }
        
        // 7. Find restrictions using this class (owl:onClass)
        String restrictionQuery = PREFIXES + """
            SELECT DISTINCT ?restriction ?onProp ?propLabel WHERE {
              ?restriction a owl:Restriction ;
                           owl:onClass <%s> ;
                           owl:onProperty ?onProp .
              OPTIONAL { ?onProp rdfs:label ?propLabel }
            }
            """.formatted(classIri);
        ResultSet restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            QuerySolution sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            usage.put("subject", sol.getResource("restriction").getURI());
            String propLabel = sol.contains("propLabel") ? sol.getLiteral("propLabel").getString() : sol.getResource("onProp").getLocalName();
            usage.put("subjectLabel", "Restriction on " + propLabel);
            usage.put("context", "Used in restriction");
            usages.add(usage);
        }
        
        // 8. Find equivalent classes
        String equivQuery = PREFIXES + """
            SELECT DISTINCT ?equiv ?label WHERE {
              {
                <%s> owl:equivalentClass ?equiv .
              } UNION {
                ?equiv owl:equivalentClass <%s> .
              }
              FILTER(isIRI(?equiv))
              OPTIONAL { ?equiv rdfs:label ?label }
            }
            """.formatted(classIri, classIri);
        ResultSet equivs = datasetService.execSelect(projectId, equivQuery);
        while (equivs.hasNext()) {
            QuerySolution sol = equivs.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "equivalent");
            usage.put("subject", sol.getResource("equiv").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("equiv").getLocalName());
            usage.put("context", "EquivalentClass");
            usages.add(usage);
        }
        
        // 9. Find union/intersection members
        String unionIntersectionQuery = PREFIXES + """
            SELECT DISTINCT ?owner ?label ?type WHERE {
              {
                ?owner owl:unionOf ?list .
                ?list rdf:rest*/rdf:first <%s> .
                BIND("union" AS ?type)
              } UNION {
                ?owner owl:intersectionOf ?list .
                ?list rdf:rest*/rdf:first <%s> .
                BIND("intersection" AS ?type)
              }
              OPTIONAL { ?owner rdfs:label ?label }
            }
            """.formatted(classIri, classIri);
        ResultSet unionIntersections = datasetService.execSelect(projectId, unionIntersectionQuery);
        while (unionIntersections.hasNext()) {
            QuerySolution sol = unionIntersections.next();
            Map<String, String> usage = new LinkedHashMap<>();
            String typeStr = sol.getLiteral("type").getString();
            usage.put("type", typeStr);
            usage.put("subject", sol.getResource("owner").getURI());
            usage.put("subjectLabel", sol.contains("label") ? sol.getLiteral("label").getString() : sol.getResource("owner").getLocalName());
            usage.put("context", "Member of " + typeStr);
            usages.add(usage);
        }
        
        // 10. Find all annotation property assertions pointing to this class
        String annotationUsageQuery = PREFIXES + """
            SELECT DISTINCT ?subject ?prop ?propLabel ?subjectLabel WHERE {
              ?subject ?prop <%s> .
              ?prop a owl:AnnotationProperty .
              OPTIONAL { ?prop rdfs:label ?propLabel }
              OPTIONAL { ?subject rdfs:label ?subjectLabel }
            }
            """.formatted(classIri);
        ResultSet annotationUsages = datasetService.execSelect(projectId, annotationUsageQuery);
        while (annotationUsages.hasNext()) {
            QuerySolution sol = annotationUsages.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "annotation");
            usage.put("subject", sol.getResource("subject").getURI());
            String subjLabel = sol.contains("subjectLabel") ? sol.getLiteral("subjectLabel").getString() : sol.getResource("subject").getLocalName();
            String propLabel = sol.contains("propLabel") ? sol.getLiteral("propLabel").getString() : sol.getResource("prop").getLocalName();
            usage.put("subjectLabel", subjLabel);
            usage.put("context", "Annotation: " + propLabel);
            usages.add(usage);
        }
        
        return usages;
    }

    public Map<String, Object> classDetails(String projectId, String classIri) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", classIri);
        
        // Get label
        String labelQuery = PREFIXES + """
            SELECT ?label WHERE {
              <%s> rdfs:label ?label
            } LIMIT 1
            """.formatted(classIri);
        ResultSet labelRs = datasetService.execSelect(projectId, labelQuery);
        if (labelRs.hasNext()) {
            details.put("label", labelRs.next().getLiteral("label").getString());
        } else {
            details.put("label", localName(classIri));
        }
        
        // Get all annotation properties for this class
        String annQuery = PREFIXES + """
            SELECT ?prop ?value WHERE {
              <%s> ?prop ?value .
              ?prop a owl:AnnotationProperty .
            }
            """.formatted(classIri);
        ResultSet annRs = datasetService.execSelect(projectId, annQuery);
        Map<String, Object> annotations = new LinkedHashMap<>();
        while (annRs.hasNext()) {
            QuerySolution sol = annRs.next();
            String propIri = sol.getResource("prop").getURI();
            String propLabel = localName(propIri);
            RDFNode valueNode = sol.get("value");
            String value = valueNode.isLiteral() ? valueNode.asLiteral().getString() : valueNode.toString();
            annotations.put(propIri, value);
        }
        details.put("annotations", annotations);
        
        // Get SubClassOf axioms
        String subClassQuery = PREFIXES + """
            SELECT ?super ?label WHERE {
              <%s> rdfs:subClassOf ?super .
              FILTER(isIRI(?super) && ?super != owl:Thing)
              OPTIONAL { ?super rdfs:label ?label }
            }
            """.formatted(classIri);
        ResultSet subClassRs = datasetService.execSelect(projectId, subClassQuery);
        List<Map<String, String>> subClassAxioms = new ArrayList<>();
        while (subClassRs.hasNext()) {
            QuerySolution sol = subClassRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String superIri = sol.getResource("super").getURI();
            axiom.put("id", superIri);
            axiom.put("type", "SubClassOf");
            axiom.put("definition", sol.contains("label") ? sol.getLiteral("label").getString() : localName(superIri));
            subClassAxioms.add(axiom);
        }
        details.put("subClassOfAxioms", subClassAxioms);
        
        return details;
    }

    /**
     * Get data property hierarchy with top-level properties
     */
    public List<PropertyDto> dataPropertyHierarchy(String projectId) {
        String query = PREFIXES + """
            SELECT DISTINCT ?prop ?label (EXISTS { ?child rdfs:subPropertyOf ?prop . FILTER(?child != ?prop) } AS ?hasChildren)
            WHERE {
              ?prop a owl:DatatypeProperty .
              FILTER NOT EXISTS { ?prop rdfs:subPropertyOf ?super . FILTER(?super != ?prop) }
              OPTIONAL { ?prop rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            """;
        
        ResultSet rs = datasetService.execSelect(projectId, query);
        List<PropertyDto> properties = new ArrayList<>();
        
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = resource(sol, "prop");
            if (iri != null) {
                PropertyDto dto = new PropertyDto();
                dto.setId(iri);
                dto.setIri(iri);
                String label = literal(sol, "label");
                dto.setLabel(label.isEmpty() ? localName(iri) : label);
                dto.setType("DatatypeProperty");
                dto.setHasChildren(sol.getLiteral("hasChildren") != null && sol.getLiteral("hasChildren").getBoolean());
                properties.add(dto);
            }
        }
        
        return properties;
    }

    /**
     * Get children of a data property
     */
    public List<PropertyDto> dataPropertyChildren(String projectId, String parentIri) {
        String query = PREFIXES + """
            SELECT DISTINCT ?prop ?label (EXISTS { ?child rdfs:subPropertyOf ?prop . FILTER(?child != ?prop) } AS ?hasChildren)
            WHERE {
              ?prop rdfs:subPropertyOf <%s> .
              FILTER(?prop != <%s>)
              OPTIONAL { ?prop rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            """.formatted(parentIri, parentIri);
        
        ResultSet rs = datasetService.execSelect(projectId, query);
        List<PropertyDto> properties = new ArrayList<>();
        
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = resource(sol, "prop");
            if (iri != null) {
                PropertyDto dto = new PropertyDto();
                dto.setId(iri);
                dto.setIri(iri);
                String label = literal(sol, "label");
                dto.setLabel(label.isEmpty() ? localName(iri) : label);
                dto.setType("DatatypeProperty");
                dto.setHasChildren(sol.getLiteral("hasChildren") != null && sol.getLiteral("hasChildren").getBoolean());
                properties.add(dto);
            }
        }
        
        return properties;
    }

    /**
     * Execute a DL query (Description Logic query)
     * Supports: subclasses, superclasses, equivalentClasses, instances
     */
    public Map<String, Object> executeDLQuery(String projectId, String classExpression, String queryType) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // For simple class IRI queries
        if (classExpression.startsWith("http://") || classExpression.startsWith("https://")) {
            String classIri = classExpression;
            List<OntologyDto.TreeNode> classes = new ArrayList<>();
            
            switch (queryType.toLowerCase()) {
                case "subclasses":
                    classes = getSubclasses(projectId, classIri, false);
                    break;
                case "directsubclasses":
                    classes = getSubclasses(projectId, classIri, true);
                    break;
                case "superclasses":
                    classes = getSuperclasses(projectId, classIri, false);
                    break;
                case "directsuperclasses":
                    classes = getSuperclasses(projectId, classIri, true);
                    break;
                case "equivalentclasses":
                    classes = getEquivalentClasses(projectId, classIri);
                    break;
                case "instances":
                    classes = getInstances(projectId, classIri);
                    break;
            }
            
            result.put("classes", classes);
            result.put("queryType", queryType);
        } else {
            // For complex class expressions, return empty for now
            // Full DL query parser would be needed for complex expressions
            result.put("classes", new ArrayList<>());
            result.put("queryType", queryType);
            result.put("message", "Complex class expressions are not yet supported. Please use a class IRI.");
        }
        
        return result;
    }

    private List<OntologyDto.TreeNode> getSubclasses(String projectId, String classIri, boolean direct) {
        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label (EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c) } AS ?hasChildren)
            WHERE {
              ?c rdfs:subClassOf%s <%s> .
              FILTER(?c != <%s>)
              OPTIONAL { ?c rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            """.formatted(direct ? "" : "+", classIri, classIri);
        
        return mapTreeNodes(projectId, query, classIri);
    }

    private List<OntologyDto.TreeNode> getSuperclasses(String projectId, String classIri, boolean direct) {
        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label (EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c) } AS ?hasChildren)
            WHERE {
              <%s> rdfs:subClassOf%s ?c .
              FILTER(?c != <%s>)
              OPTIONAL { ?c rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            """.formatted(classIri, direct ? "" : "+", classIri);
        
        return mapTreeNodes(projectId, query, null);
    }

    private List<OntologyDto.TreeNode> getEquivalentClasses(String projectId, String classIri) {
        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label (EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c) } AS ?hasChildren)
            WHERE {
              { <%s> owl:equivalentClass ?c }
              UNION
              { ?c owl:equivalentClass <%s> }
              FILTER(?c != <%s>)
              OPTIONAL { ?c rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            """.formatted(classIri, classIri, classIri);
        
        return mapTreeNodes(projectId, query, null);
    }

    private List<OntologyDto.TreeNode> getInstances(String projectId, String classIri) {
        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label
            WHERE {
              ?c a <%s> .
              OPTIONAL { ?c rdfs:label ?label }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            """.formatted(classIri);
        
        ResultSet rs = datasetService.execSelect(projectId, query);
        List<OntologyDto.TreeNode> nodes = new ArrayList<>();
        
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = resource(sol, "c");
            if (iri != null) {
                OntologyDto.TreeNode node = new OntologyDto.TreeNode();
                node.setId(iri);
                String label = literal(sol, "label");
                node.setLabel(label.isEmpty() ? localName(iri) : label);
                node.setHasChildren(false);
                nodes.add(node);
            }
        }
        
        return nodes;
    }
}


