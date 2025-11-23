package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Value;
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

    private final GraphDBDatasetService datasetService;

    public OntologyQueryService(GraphDBDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit) {
        // Get classes that are direct subclasses of owl:Thing or have no explicit superclass
        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label (EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c) } AS ?hasChildren)
            WHERE {
              {
                # Classes explicitly declared
                ?c a owl:Class .
              } UNION {
                # Classes used as subject in subClassOf
                ?c rdfs:subClassOf ?any .
              } UNION {
                # Classes used as object in subClassOf
                ?any rdfs:subClassOf ?c .
              }
              
              # Only include named classes (filter out blank nodes)
              FILTER(isIRI(?c))
              
              # Filter for top-level: either subclass of owl:Thing or no superclass at all
              FILTER (
                NOT EXISTS { 
                  ?c rdfs:subClassOf ?super . 
                  FILTER(?super != ?c && ?super != <http://www.w3.org/2002/07/owl#Thing>)
                } ||
                EXISTS {
                  ?c rdfs:subClassOf <http://www.w3.org/2002/07/owl#Thing> .
                }
              )
              
              # Exclude owl:Thing itself
              FILTER(?c != <http://www.w3.org/2002/07/owl#Thing>)
              
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
                   (GROUP_CONCAT(DISTINCT ?super; SEPARATOR="|") AS ?superProperties)
                   (GROUP_CONCAT(DISTINCT ?inverse; SEPARATOR="|") AS ?inverseProperties)
                   (GROUP_CONCAT(DISTINCT ?disjoint; SEPARATOR="|") AS ?disjointProperties)
                   (GROUP_CONCAT(DISTINCT ?equiv; SEPARATOR="|") AS ?equivalentProperties)
                   (GROUP_CONCAT(DISTINCT ?char; SEPARATOR="|") AS ?characteristics)
            WHERE {
              {
                # Explicitly typed properties
                ?prop a ?kind .
                VALUES ?kind { owl:ObjectProperty owl:DatatypeProperty }
              } UNION {
                # Properties used in domain/range/subPropertyOf but not explicitly typed
                {
                  { ?prop rdfs:domain ?any } UNION 
                  { ?prop rdfs:range ?any } UNION 
                  { ?prop rdfs:subPropertyOf ?any } UNION 
                  { ?any rdfs:subPropertyOf ?prop }
                }
                # Infer type based on usage or default to ObjectProperty
                OPTIONAL { ?prop a ?explicitKind . VALUES ?explicitKind { owl:ObjectProperty owl:DatatypeProperty } }
                BIND(COALESCE(?explicitKind, owl:ObjectProperty) AS ?kind)
                # Exclude annotation properties
                FILTER NOT EXISTS { ?prop a owl:AnnotationProperty }
                FILTER(isIRI(?prop))
              }
              %s
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:domain ?domain . FILTER(isIRI(?domain)) }
              OPTIONAL { ?prop rdfs:range ?range . FILTER(isIRI(?range)) }
              OPTIONAL { ?prop rdfs:subPropertyOf ?super . FILTER(isIRI(?super) && ?super != ?prop) }
              OPTIONAL { ?prop owl:inverseOf ?inverse . FILTER(isIRI(?inverse)) }
              OPTIONAL { ?prop owl:propertyDisjointWith ?disjoint . FILTER(isIRI(?disjoint)) }
              OPTIONAL { ?prop owl:equivalentProperty ?equiv . FILTER(isIRI(?equiv) && ?equiv != ?prop) }
              OPTIONAL { 
                ?prop a ?char . 
                FILTER(?char IN (
                  owl:FunctionalProperty, 
                  owl:InverseFunctionalProperty, 
                  owl:TransitiveProperty, 
                  owl:SymmetricProperty, 
                  owl:AsymmetricProperty, 
                  owl:ReflexiveProperty, 
                  owl:IrreflexiveProperty
                )) 
              }
            }
            GROUP BY ?prop ?kind
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            LIMIT %d OFFSET %d
            """.formatted(filter, Math.max(1, limit), Math.max(0, offset));

        System.out.println("=== PROPERTIES QUERY ===");
        System.out.println(query);
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<PropertyDto> results = new ArrayList<>();
        int count = 0;
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
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
            dto.setSuperProperties(splitPipe(literal(sol, "superProperties")));
            dto.setInverseProperties(splitPipe(literal(sol, "inverseProperties")));
            dto.setDisjointProperties(splitPipe(literal(sol, "disjointProperties")));
            dto.setEquivalentProperties(splitPipe(literal(sol, "equivalentProperties")));
            
            // Map full IRIs to simple names for characteristics (e.g. owl:FunctionalProperty -> Functional)
            List<String> chars = splitPipe(literal(sol, "characteristics"));
            if (chars != null) {
                dto.setCharacteristics(chars.stream()
                    .map(charIri -> {
                        String name = localName(charIri);
                        return name.replace("Property", ""); // FunctionalProperty -> Functional
                    })
                    .toList());
            }
            
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

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<IndividualDto> individuals = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
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
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    return Long.parseLong(countValue.stringValue());
                }
            }
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
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<AnnotationPropertyDto> props = new ArrayList<>();
        int count = 0;
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
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

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<Map<String, String>> usages = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String subjectIri = resource(sol, "subject");
            if (subjectIri == null) {
                continue;
            }
            String subjectLabel = literal(sol, "subjectLabel");
            String value = sol.hasBinding("value") ? sol.getValue("value").toString() : "";
            
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

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        Set<String> datatypes = new LinkedHashSet<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
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
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<OntologyDto.TreeNode> nodes = new ArrayList<>();
        System.out.println("=== MAPPING TREE NODES ===");
        System.out.println("Available binding names: " + rs.getBindingNames());
        int count = 0;
        while (rs.hasNext()) {
            count++;
            BindingSet sol = rs.next();
            System.out.println("Row " + count + " bindings: " + sol.getBindingNames());
            String iri = resource(sol, parentIri == null ? "c" : "child");
            if (iri == null) {
                System.out.println("Row " + count + ": IRI is null for variable '" + (parentIri == null ? "c" : "child") + "'");
                System.out.println("Row " + count + ": All values: " + sol);
                continue;
            }
            System.out.println("Row " + count + ": IRI = " + iri);
            OntologyDto.TreeNode node = new OntologyDto.TreeNode();
            node.setId(iri);
            String label = literal(sol, parentIri == null ? "label" : "label");
            node.setLabel(label.isBlank() ? localName(iri) : label);
            if (sol.hasBinding("hasChildren")) {
                Value hasChildrenValue = sol.getValue("hasChildren");
                if (hasChildrenValue.isLiteral()) {
                    node.setHasChildren(Boolean.parseBoolean(hasChildrenValue.stringValue()));
                }
            }
            node.setParent(parentIri);
            nodes.add(node);
        }
        System.out.println("=== MAPPED " + nodes.size() + " NODES FROM " + count + " ROWS ===");
        return nodes;
    }

    private String resource(BindingSet sol, String var) {
        if (sol.hasBinding(var)) {
            Value node = sol.getValue(var);
            if (node != null && node.isIRI()) {
                return node.stringValue();
            }
        }
        return null;
    }

    private String literal(BindingSet sol, String var) {
        if (sol.hasBinding(var)) {
            Value node = sol.getValue(var);
            if (node != null && node.isLiteral()) {
                return node.stringValue();
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
        TupleQueryResult rs1 = datasetService.execSelect(projectId, countQuery);
        long totalTriples = 0;
        if (rs1.hasNext()) {
            BindingSet sol = rs1.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    totalTriples = Long.parseLong(countValue.stringValue());
                }
            }
        }

        // Count OWL classes (explicit or implicit)
        String classQuery = PREFIXES + """
            SELECT (COUNT(DISTINCT ?c) AS ?count) WHERE {
              { ?c a owl:Class }
              UNION { ?c rdfs:subClassOf ?any }
              UNION { ?any rdfs:subClassOf ?c }
            }
            """;
        TupleQueryResult rs2 = datasetService.execSelect(projectId, classQuery);
        long classCount = 0;
        if (rs2.hasNext()) {
            BindingSet sol = rs2.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    classCount = Long.parseLong(countValue.stringValue());
                }
            }
        }

        // Count annotation properties
        String annQuery = PREFIXES + "SELECT (COUNT(DISTINCT ?p) AS ?count) WHERE { ?p a owl:AnnotationProperty }";
        TupleQueryResult rs3 = datasetService.execSelect(projectId, annQuery);
        long annCount = 0;
        if (rs3.hasNext()) {
            BindingSet sol = rs3.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    annCount = Long.parseLong(countValue.stringValue());
                }
            }
        }

        // Sample some triples
        String sampleQuery = "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10";
        TupleQueryResult rs4 = datasetService.execSelect(projectId, sampleQuery);
        List<String> sampleTriples = new ArrayList<>();
        while (rs4.hasNext()) {
            BindingSet sol = rs4.next();
            sampleTriples.add(sol.getValue("s") + " " + sol.getValue("p") + " " + sol.getValue("o"));
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
        TupleQueryResult subclasses = datasetService.execSelect(projectId, subclassQuery);
        while (subclasses.hasNext()) {
            BindingSet sol = subclasses.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "subclass");
            String subclassIri = resource(sol, "subclass");
            if (subclassIri != null) {
                usage.put("subject", subclassIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subclassIri));
                usage.put("context", "SubClassOf " + classIri);
                usages.add(usage);
            }
        }
        
        // 2. Find individuals of this class
        String instanceQuery = PREFIXES + """
            SELECT DISTINCT ?individual ?label WHERE {
              ?individual a <%s> .
              OPTIONAL { ?individual rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult instances = datasetService.execSelect(projectId, instanceQuery);
        while (instances.hasNext()) {
            BindingSet sol = instances.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "instance");
            String individualIri = resource(sol, "individual");
            if (individualIri != null) {
                usage.put("subject", individualIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(individualIri));
                usage.put("context", "Individual of " + classIri);
                usages.add(usage);
            }
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
        TupleQueryResult disjoints = datasetService.execSelect(projectId, disjointQuery);
        while (disjoints.hasNext()) {
            BindingSet sol = disjoints.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "disjoint");
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                usage.put("subject", disjointIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                usage.put("context", "DisjointWith");
                usages.add(usage);
            }
        }
        
        // 4. Find named superclasses
        String superclassQuery = PREFIXES + """
            SELECT DISTINCT ?superclass ?label WHERE {
              <%s> rdfs:subClassOf ?superclass .
              FILTER(isIRI(?superclass) && ?superclass != owl:Thing)
              OPTIONAL { ?superclass rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult superclasses = datasetService.execSelect(projectId, superclassQuery);
        while (superclasses.hasNext()) {
            BindingSet sol = superclasses.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "superclass");
            String superclassIri = resource(sol, "superclass");
            if (superclassIri != null) {
                usage.put("subject", superclassIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(superclassIri));
                usage.put("context", "SuperClassOf");
                usages.add(usage);
            }
        }
        
        // 5. Find properties with this class as domain
        String domainQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:domain <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult domains = datasetService.execSelect(projectId, domainQuery);
        while (domains.hasNext()) {
            BindingSet sol = domains.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "domain");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                usage.put("subject", propIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(propIri));
                usage.put("context", "Domain of property");
                usages.add(usage);
            }
        }
        
        // 6. Find properties with this class as range
        String rangeQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:range <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult ranges = datasetService.execSelect(projectId, rangeQuery);
        while (ranges.hasNext()) {
            BindingSet sol = ranges.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "range");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                usage.put("subject", propIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(propIri));
                usage.put("context", "Range of property");
                usages.add(usage);
            }
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
        TupleQueryResult restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            BindingSet sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            String restrictionIri = resource(sol, "restriction");
            String onPropIri = resource(sol, "onProp");
            if (restrictionIri != null && onPropIri != null) {
                usage.put("subject", restrictionIri);
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(onPropIri);
                usage.put("subjectLabel", "Restriction on " + propLabel);
                usage.put("context", "Used in restriction");
                usages.add(usage);
            }
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
        TupleQueryResult equivs = datasetService.execSelect(projectId, equivQuery);
        while (equivs.hasNext()) {
            BindingSet sol = equivs.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "equivalent");
            String equivIri = resource(sol, "equiv");
            if (equivIri != null) {
                usage.put("subject", equivIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(equivIri));
                usage.put("context", "EquivalentClass");
                usages.add(usage);
            }
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
        TupleQueryResult unionIntersections = datasetService.execSelect(projectId, unionIntersectionQuery);
        while (unionIntersections.hasNext()) {
            BindingSet sol = unionIntersections.next();
            Map<String, String> usage = new LinkedHashMap<>();
            String typeStr = literal(sol, "type");
            usage.put("type", typeStr);
            String ownerIri = resource(sol, "owner");
            if (ownerIri != null) {
                usage.put("subject", ownerIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(ownerIri));
                usage.put("context", "Member of " + typeStr);
                usages.add(usage);
            }
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
        TupleQueryResult annotationUsages = datasetService.execSelect(projectId, annotationUsageQuery);
        while (annotationUsages.hasNext()) {
            BindingSet sol = annotationUsages.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "annotation");
            String subjectIri = resource(sol, "subject");
            String propIri = resource(sol, "prop");
            if (subjectIri != null && propIri != null) {
                usage.put("subject", subjectIri);
                String subjLabel = sol.hasBinding("subjectLabel") ? literal(sol, "subjectLabel") : localName(subjectIri);
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(propIri);
                usage.put("subjectLabel", subjLabel);
                usage.put("context", "Annotation: " + propLabel);
                usages.add(usage);
            }
        }
        
        // 11. Find all annotation properties ON this class (annotations declared on the class itself)
        String classAnnotationsQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?value ?propLabel WHERE {
              <%s> ?prop ?value .
              ?prop a owl:AnnotationProperty .
              OPTIONAL { ?prop rdfs:label ?propLabel }
            }
            """.formatted(classIri);
        TupleQueryResult classAnnotations = datasetService.execSelect(projectId, classAnnotationsQuery);
        while (classAnnotations.hasNext()) {
            BindingSet sol = classAnnotations.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "annotation_on_class");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                // Skip standard RDF/RDFS/OWL properties that aren't custom annotations
                if (propIri.startsWith("http://www.w3.org/2000/01/rdf-schema#") && 
                    (propIri.endsWith("#label") || propIri.endsWith("#comment"))) {
                    continue;
                }
                if (propIri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) {
                    continue;
                }
                
                usage.put("subject", classIri);
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(propIri);
                String value = sol.hasBinding("value") ? sol.getValue("value").stringValue() : "";
                
                // Truncate long values for display
                if (value.length() > 100) {
                    value = value.substring(0, 97) + "...";
                }
                
                usage.put("subjectLabel", propLabel);
                usage.put("context", value);
                usage.put("annotationProperty", propIri);
                usages.add(usage);
            }
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
        TupleQueryResult labelRs = datasetService.execSelect(projectId, labelQuery);
        if (labelRs.hasNext()) {
            BindingSet labelSol = labelRs.next();
            details.put("label", literal(labelSol, "label"));
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
        TupleQueryResult annRs = datasetService.execSelect(projectId, annQuery);
        Map<String, Object> annotations = new LinkedHashMap<>();
        while (annRs.hasNext()) {
            BindingSet sol = annRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null && sol.hasBinding("value")) {
                String propLabel = localName(propIri);
                Value valueNode = sol.getValue("value");
                String value = valueNode.isLiteral() ? valueNode.stringValue() : valueNode.toString();
                annotations.put(propIri, value);
            }
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
        TupleQueryResult subClassRs = datasetService.execSelect(projectId, subClassQuery);
        List<Map<String, String>> subClassAxioms = new ArrayList<>();
        while (subClassRs.hasNext()) {
            BindingSet sol = subClassRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String superIri = resource(sol, "super");
            if (superIri != null) {
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                subClassAxioms.add(axiom);
            }
        }
        details.put("subClassOfAxioms", subClassAxioms);
        
        return details;
    }
}

