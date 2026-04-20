package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class OntologyQueryService {

    private static final Logger log = LoggerFactory.getLogger(OntologyQueryService.class);
    
    /** Thread pool for parallel SPARQL queries in classDetails */
    private static final ExecutorService QUERY_POOL = Executors.newFixedThreadPool(8);

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        """;

    private final GraphDBDatasetService datasetService;

    public OntologyQueryService(GraphDBDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    /**
     * Get top-level classes (direct children of owl:Thing or classes with no explicit superclass).
     * OPTIMIZED: Results are cached to enable instant loading on subsequent requests.
     * hasChildren is checked via EXISTS in the SPARQL query for accurate expand icons.
     */
    @Cacheable(value = "topLevelClasses", key = "#projectId + '_' + #limit")
    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit) {
        long startTime = System.currentTimeMillis();
        
        // Include EXISTS check for hasChildren so expand icons are accurate
        // Results are cached so the initial query cost is paid only once
        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label ?description ?hasChildren
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
              
              # Filter for top-level: either subclass of owl:Thing, no superclass at all,
              # or superclass is not a declared class in the loaded data (unresolved import target)
              FILTER (
                NOT EXISTS { 
                  ?c rdfs:subClassOf ?super . 
                  ?super a owl:Class .
                  FILTER(isIRI(?super) && ?super != ?c && ?super != <http://www.w3.org/2002/07/owl#Thing>)
                } ||
                EXISTS {
                  ?c rdfs:subClassOf <http://www.w3.org/2002/07/owl#Thing> .
                }
              )
              
              # Exclude owl:Thing itself
              FILTER(?c != <http://www.w3.org/2002/07/owl#Thing>)
              
              OPTIONAL { ?c rdfs:label ?label }
              OPTIONAL { ?c rdfs:comment ?description }
              BIND(EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c) } AS ?hasChildren)
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            LIMIT %d
            """.formatted(Math.max(1, limit));
        
        log.info("🔍 [PERF] Loading top-level classes for project: {}", projectId);
        List<OntologyDto.TreeNode> result = mapTreeNodes(projectId, query, null);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded {} top-level classes in {}ms (cached for future requests)", result.size(), duration);
        
        return result;
    }


    /**
     * Get ALL classes (including children) in a single SPARQL query.
     * Used by the graph visualization to render the full class hierarchy.
     */
    @Cacheable(value = "allClasses", key = "#projectId + '_' + #limit")
    public List<OntologyDto.TreeNode> allClasses(String projectId, int limit) {
        long startTime = System.currentTimeMillis();

        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label ?description ?parent
            WHERE {
              {
                ?c a owl:Class .
              } UNION {
                ?c rdfs:subClassOf ?any .
              } UNION {
                ?any rdfs:subClassOf ?c .
              }
              FILTER(isIRI(?c))
              FILTER(?c != <http://www.w3.org/2002/07/owl#Thing>)
              OPTIONAL { ?c rdfs:label ?label }
              OPTIONAL { ?c rdfs:comment ?description }
              OPTIONAL {
                ?c rdfs:subClassOf ?parent .
                FILTER(isIRI(?parent) && ?parent != ?c)
              }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            LIMIT %d
            """.formatted(Math.max(1, limit));

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        Map<String, OntologyDto.TreeNode> nodeMap = new LinkedHashMap<>();

        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "c");
            if (iri == null) continue;

            OntologyDto.TreeNode node = nodeMap.get(iri);
            if (node == null) {
                node = new OntologyDto.TreeNode();
                node.setId(iri);
                String lbl = literal(sol, "label");
                node.setLabel(lbl.isBlank() ? localName(iri) : lbl);
                node.setDescription(literal(sol, "description"));
                node.setHasChildren(true);
                nodeMap.put(iri, node);
            }

            String parentIri = resource(sol, "parent");
            if (parentIri != null) {
                node.setParent(parentIri);
            }
        }

        List<OntologyDto.TreeNode> result = new ArrayList<>(nodeMap.values());
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded {} total classes in {}ms for project {}", result.size(), duration, projectId);

        return result;
    }

    /**
     * Get children of a specific class.
     * OPTIMIZED: Results are cached for faster subsequent access.
     * hasChildren is checked via EXISTS in the SPARQL query for accurate expand icons.
     */
    @Cacheable(value = "classChildren", key = "#projectId + '_' + #parentIri + '_' + #limit + '_' + #offset")
    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        
        // Include EXISTS check for hasChildren so expand icons are accurate
        // Results are cached so the query cost is paid only once per parent
        String query = PREFIXES + """
            SELECT ?child ?label ?description ?hasChildren
            WHERE {
              ?child rdfs:subClassOf <%s> .
              FILTER(?child != <%s>)
              OPTIONAL { ?child rdfs:label ?label }
              OPTIONAL { ?child rdfs:comment ?description }
              BIND(EXISTS { ?grandchild rdfs:subClassOf ?child . FILTER(?grandchild != ?child) } AS ?hasChildren)
            }
            ORDER BY COALESCE(LCASE(?label), STR(?child))
            LIMIT %d OFFSET %d
            """.formatted(parentIri, parentIri, Math.max(1, limit), Math.max(0, offset));
        
        List<OntologyDto.TreeNode> result = mapTreeNodes(projectId, query, parentIri);
        
        long duration = System.currentTimeMillis() - startTime;
        log.debug("✅ [PERF] Loaded {} children for {} in {}ms", result.size(), parentIri, duration);
        
        return result;
    }

    /**
     * Get all properties for a project.
     * OPTIMIZED: Cached + simplified query (details loaded on-demand per property).
     */
    @Cacheable(value = "ontologyProperties", key = "#projectId + '_' + #type + '_' + #limit + '_' + #offset")
    public List<PropertyDto> properties(String projectId, String type, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String filter = switch (normalize(type)) {
            case "object" -> "FILTER(?kind = owl:ObjectProperty)";
            case "data" -> "FILTER(?kind = owl:DatatypeProperty)";
            default -> "";
        };

        // OPTIMIZED: Simplified query - load only essential fields for the tree view
        // Detailed property info (domain, range, characteristics) is loaded on-demand when selected
        String query = PREFIXES + """
            SELECT ?prop (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description) ?kind
                   (GROUP_CONCAT(DISTINCT STR(?super); SEPARATOR="|") AS ?superProperties)
            WHERE {
              ?prop a ?kind .
              FILTER(?kind IN (owl:ObjectProperty, owl:DatatypeProperty))
              FILTER(?prop != owl:topObjectProperty && ?prop != owl:topDataProperty)
              %s
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:comment ?cmt }
              OPTIONAL { ?prop rdfs:subPropertyOf ?super . FILTER(isIRI(?super) && ?super != ?prop) }
            }
            GROUP BY ?prop ?kind
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            LIMIT %d OFFSET %d
            """.formatted(filter, Math.max(1, limit), Math.max(0, offset));

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<PropertyDto> results = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "prop");
            if (iri == null) {
                continue;
            }
            PropertyDto dto = new PropertyDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            String description = literal(sol, "description");
            dto.setDescription(description);
            String kind = resource(sol, "kind");
            dto.setType(localName(kind));
            dto.setSuperProperties(splitPipe(literal(sol, "superProperties")));
            results.add(dto);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded {} properties in {}ms for project {}", results.size(), duration, projectId);
        return results;
    }

    /**
     * Get detailed info for a single property (domains, ranges, characteristics, etc.).
     * Called on-demand when a property is selected in the UI.
     */
    public PropertyDto propertyDetail(String projectId, String propertyIri) {
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT ?prop (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description) ?kind
                   (GROUP_CONCAT(DISTINCT STR(?domain); SEPARATOR="|") AS ?domains)
                   (GROUP_CONCAT(DISTINCT STR(?range); SEPARATOR="|") AS ?ranges)
                   (GROUP_CONCAT(DISTINCT STR(?super); SEPARATOR="|") AS ?superProperties)
                   (GROUP_CONCAT(DISTINCT STR(?inverse); SEPARATOR="|") AS ?inverseProperties)
                   (GROUP_CONCAT(DISTINCT STR(?disjoint); SEPARATOR="|") AS ?disjointProperties)
                   (GROUP_CONCAT(DISTINCT STR(?equiv); SEPARATOR="|") AS ?equivalentProperties)
                   (GROUP_CONCAT(DISTINCT STR(?char); SEPARATOR="|") AS ?characteristics)
            WHERE {
              BIND(<%s> AS ?prop)
              ?prop a ?kind .
              FILTER(?kind IN (owl:ObjectProperty, owl:DatatypeProperty))
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:comment ?cmt }
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
            """.formatted(propertyIri);

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "prop");
            if (iri == null) return new PropertyDto();
            PropertyDto dto = new PropertyDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            dto.setDescription(literal(sol, "description"));
            dto.setType(localName(resource(sol, "kind")));
            dto.setDomains(splitPipe(literal(sol, "domains")));
            dto.setRanges(splitPipe(literal(sol, "ranges")));
            dto.setSuperProperties(splitPipe(literal(sol, "superProperties")));
            dto.setInverseProperties(splitPipe(literal(sol, "inverseProperties")));
            dto.setDisjointProperties(splitPipe(literal(sol, "disjointProperties")));
            dto.setEquivalentProperties(splitPipe(literal(sol, "equivalentProperties")));
            List<String> chars = splitPipe(literal(sol, "characteristics"));
            if (chars != null) {
                dto.setCharacteristics(chars.stream()
                    .map(charIri -> localName(charIri).replace("Property", ""))
                    .toList());
            }
            long duration = System.currentTimeMillis() - startTime;
            log.info("[PERF] propertyDetail for {} completed in {}ms project={}", localName(propertyIri), duration, projectId);
            return dto;
        }
        long duration = System.currentTimeMillis() - startTime;
        log.warn("[PERF] propertyDetail for {} returned empty in {}ms project={}", propertyIri, duration, projectId);
        return new PropertyDto();
    }

    /**
     * Get individuals for a project.
     * OPTIMIZED: Cached for repeated access.
     */
    @Cacheable(value = "ontologyIndividuals", key = "#projectId + '_' + #limit + '_' + #offset")
    public List<IndividualDto> individuals(String projectId, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT ?ind (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description)
                   (GROUP_CONCAT(DISTINCT ?type; SEPARATOR="|") AS ?types)
            WHERE {
              ?ind a owl:NamedIndividual .
              OPTIONAL { ?ind rdfs:label ?lbl }
              OPTIONAL { ?ind rdfs:comment ?cmt }
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
            String description = literal(sol, "description");
            dto.setDescription(description);
            dto.setTypes(splitPipe(literal(sol, "types")));
            individuals.add(dto);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] individuals loaded {} in {}ms project={}", individuals.size(), duration, projectId);
        return individuals;
    }

    @Cacheable(value = "individualCount", key = "#projectId")
    public long individualCount(String projectId) {
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT (COUNT(DISTINCT ?ind) AS ?count)
            WHERE { ?ind a owl:NamedIndividual . }
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        long count = 0;
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    count = Long.parseLong(countValue.stringValue());
                }
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] individualCount={} in {}ms project={}", count, duration, projectId);
        return count;
    }

    public List<AnnotationPropertyDto> annotationProperties(String projectId, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT DISTINCT ?prop (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description)
            WHERE {
              ?prop a owl:AnnotationProperty .
              FILTER(!isBlank(?prop))
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:comment ?cmt }
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
            String description = literal(sol, "description");
            dto.setDescription(description);
            props.add(dto);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] annotationProperties loaded {} (rows={}) in {}ms project={}", props.size(), count, duration, projectId);
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

    public List<Map<String, String>> datatypeUsage(String projectId, String datatypeIri) {
        List<Map<String, String>> usages = new ArrayList<>();
        
        // 1. Find data properties with this range
        String rangeQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:range <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(datatypeIri);
        TupleQueryResult ranges = datasetService.execSelect(projectId, rangeQuery);
        while (ranges.hasNext()) {
            BindingSet sol = ranges.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "range");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                usage.put("subject", propIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(propIri));
                usage.put("context", "Range of data property");
                usages.add(usage);
            }
        }
        
        // 2. Find restrictions using this datatype
        String restrictionQuery = PREFIXES + """
            SELECT DISTINCT ?class ?label WHERE {
              ?class rdfs:subClassOf|owl:equivalentClass ?restriction .
              ?restriction owl:onDataRange|owl:someValuesFrom|owl:allValuesFrom <%s> .
              FILTER(isIRI(?class))
              OPTIONAL { ?class rdfs:label ?label }
            }
            """.formatted(datatypeIri);
        TupleQueryResult restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            BindingSet sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            String classIri = resource(sol, "class");
            if (classIri != null) {
                usage.put("subject", classIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(classIri));
                usage.put("context", "Used in data restriction");
                usages.add(usage);
            }
        }

        return usages;
    }

    public List<Map<String, String>> individualUsage(String projectId, String individualIri) {
        List<Map<String, String>> usages = new ArrayList<>();
        
        // 1. Find object property assertions where this is the object
        String assertionQuery = PREFIXES + """
            SELECT DISTINCT ?subject ?prop ?label WHERE {
              ?subject ?prop <%s> .
              ?prop a owl:ObjectProperty .
              OPTIONAL { ?subject rdfs:label ?label }
            }
            """.formatted(individualIri);
        TupleQueryResult assertions = datasetService.execSelect(projectId, assertionQuery);
        while (assertions.hasNext()) {
            BindingSet sol = assertions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "assertion");
            String subjectIri = resource(sol, "subject");
            String propIri = resource(sol, "prop");
            if (subjectIri != null) {
                usage.put("subject", subjectIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subjectIri));
                usage.put("predicate", propIri);
                usage.put("context", "Object of " + localName(propIri));
                usages.add(usage);
            }
        }
        
        // 2. Find SameIndividual/DifferentIndividuals
        String sameDiffQuery = PREFIXES + """
            SELECT DISTINCT ?other ?type ?label WHERE {
              {
                <%s> owl:sameAs ?other .
                BIND("same" AS ?type)
              } UNION {
                ?other owl:sameAs <%s> .
                BIND("same" AS ?type)
              } UNION {
                <%s> owl:differentFrom ?other .
                BIND("different" AS ?type)
              } UNION {
                ?other owl:differentFrom <%s> .
                BIND("different" AS ?type)
              }
              OPTIONAL { ?other rdfs:label ?label }
            }
            """.formatted(individualIri, individualIri, individualIri, individualIri);
        TupleQueryResult sameDiffs = datasetService.execSelect(projectId, sameDiffQuery);
        while (sameDiffs.hasNext()) {
            BindingSet sol = sameDiffs.next();
            Map<String, String> usage = new LinkedHashMap<>();
            String type = sol.hasBinding("type") ? literal(sol, "type") : "unknown";
            usage.put("type", type);
            String otherIri = resource(sol, "other");
            if (otherIri != null) {
                usage.put("subject", otherIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(otherIri));
                usage.put("context", type.equals("same") ? "SameIndividualAs" : "DifferentIndividualFrom");
                usages.add(usage);
            }
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
            String description = literal(sol, "description");
            node.setDescription(description);
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

    private int literalToInt(BindingSet sol, String var) {
        if (sol.hasBinding(var)) {
            Value node = sol.getValue(var);
            if (node != null && node.isLiteral()) {
                try {
                    return Integer.parseInt(node.stringValue());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
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

    /**
     * Format an IRI with proper prefix for display (e.g., owl:topObjectProperty instead of topObjectProperty)
     */
    private String formatIriWithPrefix(String iri) {
        if (iri == null || iri.isBlank()) {
            return "";
        }
        // Handle OWL namespace
        if (iri.startsWith("http://www.w3.org/2002/07/owl#")) {
            return "owl:" + iri.substring("http://www.w3.org/2002/07/owl#".length());
        }
        // Handle RDF namespace
        if (iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
            return "rdf:" + iri.substring("http://www.w3.org/1999/02/22-rdf-syntax-ns#".length());
        }
        // Handle RDFS namespace
        if (iri.startsWith("http://www.w3.org/2000/01/rdf-schema#")) {
            return "rdfs:" + iri.substring("http://www.w3.org/2000/01/rdf-schema#".length());
        }
        // Handle XSD namespace
        if (iri.startsWith("http://www.w3.org/2001/XMLSchema#")) {
            return "xsd:" + iri.substring("http://www.w3.org/2001/XMLSchema#".length());
        }
        // Default to local name for custom ontology entities
        return localName(iri);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public List<String> ontologyImports(String projectId) {
        String query = PREFIXES + """
            SELECT DISTINCT ?import WHERE {
              ?ont a owl:Ontology .
              ?ont owl:imports ?import .
            }
            ORDER BY ?import
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<String> imports = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            Value importVal = sol.getValue("import");
            if (importVal != null) {
                imports.add(importVal.stringValue());
            }
        }
        return imports;
    }

    public List<Map<String, String>> generalClassAxioms(String projectId, int limit) {
        String query = PREFIXES + """
            SELECT DISTINCT ?sub ?super ?label WHERE {
              ?sub rdfs:subClassOf ?super .
              FILTER(isBlank(?sub))
              OPTIONAL { ?super rdfs:label ?label }
            }
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<Map<String, String>> axioms = new ArrayList<>();
        int count = 0;
        while (rs.hasNext() && count < limit) {
            BindingSet sol = rs.next();
            Value subVal = sol.getValue("sub");
            String subExpr = subVal != null ? subVal.stringValue() : "Anonymous class expression";
            String superIri = resource(sol, "super");
            String superLabel = sol.hasBinding("label") ? literal(sol, "label") : localName(superIri);

            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("subExpression", subExpr);
            axiom.put("superClassIri", superIri);
            axiom.put("superClassLabel", superLabel);
            axiom.put("definition", "Anonymous class expression <= " + (superLabel.isBlank() ? superIri : superLabel));
            axioms.add(axiom);
            count++;
        }
        return axioms;
    }

    @Cacheable(value = "debugInfo", key = "#projectId")
    public Map<String, Object> debugInfo(String projectId) {
        long startTime = System.currentTimeMillis();
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

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] debugInfo totalTriples={} classes={} in {}ms project={}", totalTriples, classCount, duration, projectId);
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
              {
                ?prop a owl:AnnotationProperty .
              } UNION {
                VALUES ?prop { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
              }
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
              FILTER(isLiteral(?value) || isIRI(?value))
              {
                ?prop a owl:AnnotationProperty .
              } UNION {
                VALUES ?prop { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
              }
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
                // Skip rdf:type as it's shown elsewhere
                if (propIri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) {
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

    public List<Map<String, String>> propertyUsage(String projectId, String propertyIri) {
        List<Map<String, String>> usages = new ArrayList<>();
        
        // 1. Find domains
        String domainQuery = PREFIXES + """
            SELECT DISTINCT ?domain ?label WHERE {
              <%s> rdfs:domain ?domain .
              FILTER(isIRI(?domain))
              OPTIONAL { ?domain rdfs:label ?label }
            }
            """.formatted(propertyIri);
        TupleQueryResult domains = datasetService.execSelect(projectId, domainQuery);
        while (domains.hasNext()) {
            BindingSet sol = domains.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "domain");
            String domainIri = resource(sol, "domain");
            if (domainIri != null) {
                usage.put("subject", domainIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(domainIri));
                usage.put("context", "Domain of property");
                usages.add(usage);
            }
        }
        
        // 2. Find ranges
        String rangeQuery = PREFIXES + """
            SELECT DISTINCT ?range ?label WHERE {
              <%s> rdfs:range ?range .
              FILTER(isIRI(?range))
              OPTIONAL { ?range rdfs:label ?label }
            }
            """.formatted(propertyIri);
        TupleQueryResult ranges = datasetService.execSelect(projectId, rangeQuery);
        while (ranges.hasNext()) {
            BindingSet sol = ranges.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "range");
            String rangeIri = resource(sol, "range");
            if (rangeIri != null) {
                usage.put("subject", rangeIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(rangeIri));
                usage.put("context", "Range of property");
                usages.add(usage);
            }
        }
        
        // 3. Find subproperties
        String subPropQuery = PREFIXES + """
            SELECT DISTINCT ?sub ?label WHERE {
              ?sub rdfs:subPropertyOf <%s> .
              FILTER(isIRI(?sub) && ?sub != <%s>)
              OPTIONAL { ?sub rdfs:label ?label }
            }
            """.formatted(propertyIri, propertyIri);
        TupleQueryResult subProps = datasetService.execSelect(projectId, subPropQuery);
        while (subProps.hasNext()) {
            BindingSet sol = subProps.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "subproperty");
            String subIri = resource(sol, "sub");
            if (subIri != null) {
                usage.put("subject", subIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subIri));
                usage.put("context", "SubPropertyOf");
                usages.add(usage);
            }
        }

        // 4. Find superproperties
        String superPropQuery = PREFIXES + """
            SELECT DISTINCT ?super ?label WHERE {
              <%s> rdfs:subPropertyOf ?super .
              FILTER(isIRI(?super) && ?super != <%s>)
              OPTIONAL { ?super rdfs:label ?label }
            }
            """.formatted(propertyIri, propertyIri);
        TupleQueryResult superProps = datasetService.execSelect(projectId, superPropQuery);
        while (superProps.hasNext()) {
            BindingSet sol = superProps.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "superproperty");
            String superIri = resource(sol, "super");
            if (superIri != null) {
                usage.put("subject", superIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                usage.put("context", "SuperPropertyOf");
                usages.add(usage);
            }
        }
        
        // 5. Find property assertions in individuals
        String assertionQuery = PREFIXES + """
            SELECT DISTINCT ?subject ?label WHERE {
              ?subject <%s> ?any .
              OPTIONAL { ?subject rdfs:label ?label }
            }
            LIMIT 1000
            """.formatted(propertyIri);
        TupleQueryResult assertions = datasetService.execSelect(projectId, assertionQuery);
        while (assertions.hasNext()) {
            BindingSet sol = assertions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "assertion");
            String subjectIri = resource(sol, "subject");
            if (subjectIri != null) {
                usage.put("subject", subjectIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subjectIri));
                usage.put("context", "Property assertion");
                usages.add(usage);
            }
        }
        
        // 6. Find restrictions using this property
        String restrictionQuery = PREFIXES + """
            SELECT DISTINCT ?class ?label WHERE {
              ?class rdfs:subClassOf ?restriction .
              ?restriction a owl:Restriction ;
                           owl:onProperty <%s> .
              FILTER(isIRI(?class))
              OPTIONAL { ?class rdfs:label ?label }
            }
            """.formatted(propertyIri);
        TupleQueryResult restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            BindingSet sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            String classIri = resource(sol, "class");
            if (classIri != null) {
                usage.put("subject", classIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(classIri));
                usage.put("context", "Used in restriction");
                usages.add(usage);
            }
        }
        
        return usages;
    }

    @Cacheable(value = "classDetails", key = "#projectId + '_' + #classIri")
    public Map<String, Object> classDetails(String projectId, String classIri) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", classIri);
        
        // ===== PARALLEL EXECUTION: Run all independent SPARQL queries concurrently =====
        // Each query is independent (read-only), so they can all run in parallel.
        // This reduces total time from sum(all queries) to max(slowest query).
        
        // --- Annotations query ---
        CompletableFuture<TupleQueryResult> annFuture = CompletableFuture.supplyAsync(() -> {
            String annQuery = PREFIXES + """
                SELECT ?prop ?value WHERE {
                  <%s> ?prop ?value .
                  FILTER(isLiteral(?value) || isIRI(?value))
                  {
                    ?prop a owl:AnnotationProperty .
                  } UNION {
                    VALUES ?prop { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
                  }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, annQuery);
        }, QUERY_POOL);
        
        // --- SubClassOf named superclasses ---
        CompletableFuture<TupleQueryResult> subClassFuture = CompletableFuture.supplyAsync(() -> {
            String subClassQuery = PREFIXES + """
                SELECT DISTINCT ?super ?label WHERE {
                  <%s> rdfs:subClassOf ?super .
                  FILTER(isIRI(?super))
                  FILTER(?super != owl:Thing)
                  FILTER(?super != <%s>)
                  FILTER(?super != owl:Nothing)
                  FILTER(!STRSTARTS(STR(?super), "http://www.w3.org/2002/07/owl#"))
                  FILTER(!STRSTARTS(STR(?super), "http://www.w3.org/2000/01/rdf-schema#"))
                  OPTIONAL { ?super rdfs:label ?label }
                }
                ORDER BY ?label
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, subClassQuery);
        }, QUERY_POOL);
        
        // --- SubClassOf restrictions ---
        CompletableFuture<TupleQueryResult> subClassRestrictionFuture = CompletableFuture.supplyAsync(() -> {
            String subClassRestrictionQuery = PREFIXES + """
                SELECT DISTINCT ?restriction ?prop ?propLabel ?restrictionType ?filler ?fillerLabel ?card ?propType WHERE {
                  <%s> rdfs:subClassOf ?restriction .
                  ?restriction a owl:Restriction ;
                              owl:onProperty ?prop .
                  OPTIONAL { ?prop rdfs:label ?propLabel }
                  OPTIONAL { ?prop a ?propType . FILTER(?propType IN (owl:ObjectProperty, owl:DatatypeProperty)) }
                  OPTIONAL {
                    ?restriction owl:someValuesFrom ?filler .
                    BIND("some" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:allValuesFrom ?filler .
                    BIND("only" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:hasValue ?filler .
                    BIND("value" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:hasSelf true .
                    BIND("Self" AS ?filler)
                    BIND("some" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:minQualifiedCardinality ?card .
                    OPTIONAL { ?restriction owl:onClass ?filler }
                    OPTIONAL { ?restriction owl:onDataRange ?filler }
                    BIND("min" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:maxQualifiedCardinality ?card .
                    OPTIONAL { ?restriction owl:onClass ?filler }
                    OPTIONAL { ?restriction owl:onDataRange ?filler }
                    BIND("max" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:qualifiedCardinality ?card .
                    OPTIONAL { ?restriction owl:onClass ?filler }
                    OPTIONAL { ?restriction owl:onDataRange ?filler }
                    BIND("exactly" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:minCardinality ?card .
                    FILTER NOT EXISTS { ?restriction owl:minQualifiedCardinality ?any }
                    BIND("min" AS ?restrictionType)
                    BIND(owl:Thing AS ?filler)
                  }
                  OPTIONAL {
                    ?restriction owl:maxCardinality ?card .
                    FILTER NOT EXISTS { ?restriction owl:maxQualifiedCardinality ?any }
                    BIND("max" AS ?restrictionType)
                    BIND(owl:Thing AS ?filler)
                  }
                  OPTIONAL {
                    ?restriction owl:cardinality ?card .
                    FILTER NOT EXISTS { ?restriction owl:qualifiedCardinality ?any }
                    BIND("exactly" AS ?restrictionType)
                    BIND(owl:Thing AS ?filler)
                  }
                  OPTIONAL { ?filler rdfs:label ?fillerLabel }
                  FILTER(BOUND(?restrictionType))
                }
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, subClassRestrictionQuery);
        }, QUERY_POOL);
        
        // --- SubClassOf intersection ---
        CompletableFuture<TupleQueryResult> intersectionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?member ?memberLabel WHERE {
                  <%s> rdfs:subClassOf ?bnode .
                  ?bnode owl:intersectionOf ?list .
                  ?list rdf:rest*/rdf:first ?member .
                  FILTER(isIRI(?member))
                  OPTIONAL { ?member rdfs:label ?memberLabel }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- SubClassOf union ---
        CompletableFuture<TupleQueryResult> unionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?member ?memberLabel WHERE {
                  <%s> rdfs:subClassOf ?bnode .
                  ?bnode owl:unionOf ?list .
                  ?list rdf:rest*/rdf:first ?member .
                  FILTER(isIRI(?member))
                  OPTIONAL { ?member rdfs:label ?memberLabel }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- SubClassOf complement ---
        CompletableFuture<TupleQueryResult> complementFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?complement ?complementLabel WHERE {
                  <%s> rdfs:subClassOf ?bnode .
                  ?bnode owl:complementOf ?complement .
                  FILTER(isIRI(?complement))
                  OPTIONAL { ?complement rdfs:label ?complementLabel }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- EquivalentClass simple ---
        CompletableFuture<TupleQueryResult> equivFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?equiv ?label WHERE {
                  <%s> owl:equivalentClass ?equiv .
                  FILTER(isIRI(?equiv) && ?equiv != <%s>)
                  OPTIONAL { ?equiv rdfs:label ?label }
                }
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- EquivalentClass restrictions ---
        CompletableFuture<TupleQueryResult> equivRestrictionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?restriction ?prop ?propLabel ?restrictionType ?filler ?fillerLabel ?card WHERE {
                  <%s> owl:equivalentClass ?restriction .
                  ?restriction a owl:Restriction ;
                              owl:onProperty ?prop .
                  OPTIONAL { ?prop rdfs:label ?propLabel }
                  OPTIONAL {
                    ?restriction owl:someValuesFrom ?filler .
                    BIND("some" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:allValuesFrom ?filler .
                    BIND("only" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:hasValue ?filler .
                    BIND("value" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:hasSelf true .
                    BIND("Self" AS ?filler)
                    BIND("some" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:minQualifiedCardinality ?card .
                    OPTIONAL { ?restriction owl:onClass ?filler }
                    OPTIONAL { ?restriction owl:onDataRange ?filler }
                    BIND("min" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:maxQualifiedCardinality ?card .
                    OPTIONAL { ?restriction owl:onClass ?filler }
                    OPTIONAL { ?restriction owl:onDataRange ?filler }
                    BIND("max" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:qualifiedCardinality ?card .
                    OPTIONAL { ?restriction owl:onClass ?filler }
                    OPTIONAL { ?restriction owl:onDataRange ?filler }
                    BIND("exactly" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:minCardinality ?card .
                    FILTER NOT EXISTS { ?restriction owl:minQualifiedCardinality ?any }
                    BIND("min" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:maxCardinality ?card .
                    FILTER NOT EXISTS { ?restriction owl:maxQualifiedCardinality ?any }
                    BIND("max" AS ?restrictionType)
                  }
                  OPTIONAL {
                    ?restriction owl:cardinality ?card .
                    FILTER NOT EXISTS { ?restriction owl:qualifiedCardinality ?any }
                    BIND("exactly" AS ?restrictionType)
                  }
                  OPTIONAL { ?filler rdfs:label ?fillerLabel }
                  FILTER(BOUND(?restrictionType))
                }
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- EquivalentClass intersection ---
        CompletableFuture<TupleQueryResult> equivIntersectionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?member ?memberLabel WHERE {
                  <%s> owl:equivalentClass ?bnode .
                  ?bnode owl:intersectionOf ?list .
                  ?list rdf:rest*/rdf:first ?member .
                  FILTER(isIRI(?member))
                  OPTIONAL { ?member rdfs:label ?memberLabel }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- EquivalentClass union ---
        CompletableFuture<TupleQueryResult> equivUnionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?member ?memberLabel WHERE {
                  <%s> owl:equivalentClass ?bnode .
                  ?bnode owl:unionOf ?list .
                  ?list rdf:rest*/rdf:first ?member .
                  FILTER(isIRI(?member))
                  OPTIONAL { ?member rdfs:label ?memberLabel }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- EquivalentClass complement ---
        CompletableFuture<TupleQueryResult> equivComplementFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?complement ?complementLabel WHERE {
                  <%s> owl:equivalentClass ?bnode .
                  ?bnode owl:complementOf ?complement .
                  FILTER(isIRI(?complement))
                  OPTIONAL { ?complement rdfs:label ?complementLabel }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- EquivalentClass oneOf ---
        CompletableFuture<TupleQueryResult> equivOneOfFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?individual ?indLabel WHERE {
                  <%s> owl:equivalentClass ?bnode .
                  ?bnode owl:oneOf ?list .
                  ?list rdf:rest*/rdf:first ?individual .
                  OPTIONAL { ?individual rdfs:label ?indLabel }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- DisjointWith ---
        CompletableFuture<TupleQueryResult> disjointFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?disjoint ?label WHERE {
                  {
                    <%s> owl:disjointWith ?disjoint .
                  } UNION {
                    ?disjoint owl:disjointWith <%s> .
                  } UNION {
                    ?allDisjoint a owl:AllDisjointClasses ;
                                 owl:members ?list .
                    ?list rdf:rest*/rdf:first <%s> .
                    ?list rdf:rest*/rdf:first ?disjoint .
                    FILTER(?disjoint != <%s>)
                  }
                  FILTER(isIRI(?disjoint) && ?disjoint != <%s>)
                  OPTIONAL { ?disjoint rdfs:label ?label }
                }
                ORDER BY ?label
                """.formatted(classIri, classIri, classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- DisjointUnionOf ---
        CompletableFuture<TupleQueryResult> disjointUnionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?list ?member WHERE {
                  <%s> owl:disjointUnionOf ?list .
                  ?list rdf:rest*/rdf:first ?member .
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- HasKey ---
        CompletableFuture<TupleQueryResult> hasKeyFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?keyList ?prop WHERE {
                  <%s> owl:hasKey ?keyList .
                  ?keyList rdf:rest*/rdf:first ?prop .
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- Inferred equivalent classes ---
        CompletableFuture<TupleQueryResult> inferredEquivFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?equiv ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    <%s> owl:equivalentClass ?equiv .
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      <%s> owl:equivalentClass ?equiv .
                    }
                  }
                  FILTER(isIRI(?equiv) && ?equiv != <%s>)
                  OPTIONAL { ?equiv rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- Inferred superclasses ---
        CompletableFuture<TupleQueryResult> inferredSuperFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?super ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    <%s> rdfs:subClassOf ?super .
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      <%s> rdfs:subClassOf ?super .
                    }
                  }
                  FILTER(isIRI(?super) && ?super != owl:Thing && ?super != <%s>)
                  OPTIONAL { ?super rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- Inferred disjoint classes ---
        CompletableFuture<TupleQueryResult> inferredDisjointFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?disjoint ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    {
                      <%s> owl:disjointWith ?disjoint .
                    } UNION {
                      ?disjoint owl:disjointWith <%s> .
                    }
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      {
                        <%s> owl:disjointWith ?disjoint .
                      } UNION {
                        ?disjoint owl:disjointWith <%s> .
                      }
                    }
                  }
                  FILTER(isIRI(?disjoint) && ?disjoint != <%s>)
                  OPTIONAL { ?disjoint rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- GCI axioms ---
        CompletableFuture<TupleQueryResult> gciFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?subExpr ?superClass WHERE {
                  ?subExpr rdfs:subClassOf ?superClass .
                  ?subExpr ?p ?o .
                  FILTER(isBlank(?subExpr))
                  FILTER(?o = <%s> || ?superClass = <%s>)
                }
                LIMIT 20
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // --- Anonymous ancestor superclasses ---
        CompletableFuture<TupleQueryResult> ancestorFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?super ?label WHERE {
                  <%s> rdfs:subClassOf+ ?ancestor .
                  ?ancestor rdfs:subClassOf ?super .
                  FILTER(isBlank(?super) || (?super != owl:Thing && ?super != <%s>))
                  OPTIONAL { ?super rdfs:label ?label }
                }
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, QUERY_POOL);
        
        // ===== Wait for all queries to complete =====
        CompletableFuture.allOf(
            annFuture, subClassFuture, subClassRestrictionFuture, intersectionFuture,
            unionFuture, complementFuture, equivFuture, equivRestrictionFuture,
            equivIntersectionFuture, equivUnionFuture, equivComplementFuture, equivOneOfFuture,
            disjointFuture, disjointUnionFuture, hasKeyFuture,
            inferredEquivFuture, inferredSuperFuture, inferredDisjointFuture,
            gciFuture, ancestorFuture
        ).join();
        
        long queryTime = System.currentTimeMillis() - startTime;
        log.info("[PERF] classDetails all parallel queries completed in {}ms for {}", queryTime, localName(classIri));
        
        // ===== Process results (all in-memory, very fast) =====
        
        // --- Process annotations ---
        TupleQueryResult annRs = annFuture.join();
        Map<String, Object> annotations = new LinkedHashMap<>();
        String label = null;
        while (annRs.hasNext()) {
            BindingSet sol = annRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null && sol.hasBinding("value")) {
                Value valueNode = sol.getValue("value");
                String value = valueNode.isLiteral() ? valueNode.stringValue() : valueNode.toString();
                annotations.put(propIri, value);
                if (label == null && propIri.endsWith("#label")) {
                    label = value;
                }
            }
        }
        details.put("label", label != null ? label : localName(classIri));
        details.put("annotations", annotations);
        
        // --- Process SubClassOf axioms ---
        List<Map<String, String>> subClassAxioms = new ArrayList<>();
        TupleQueryResult subClassRs = subClassFuture.join();
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
        
        // --- Process SubClassOf restrictions ---
        TupleQueryResult subClassRestrictionRs = subClassRestrictionFuture.join();
        Set<String> seenRestrictions = new LinkedHashSet<>();
        while (subClassRestrictionRs.hasNext()) {
            BindingSet sol = subClassRestrictionRs.next();
            String restrictionNode = sol.getValue("restriction").stringValue();
            if (seenRestrictions.contains(restrictionNode)) {
                continue;
            }
            seenRestrictions.add(restrictionNode);
            Map<String, String> axiom = new LinkedHashMap<>();
            String propIri = resource(sol, "prop");
            String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : formatIriWithPrefix(propIri);
            String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
            String fillerIri = sol.hasBinding("filler") ? sol.getValue("filler").stringValue() : "";
            String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel") : formatIriWithPrefix(fillerIri);
            String cardinality = sol.hasBinding("card") ? literal(sol, "card") : "";
            String definition;
            if (!cardinality.isEmpty()) {
                definition = propLabel + " " + restrictionType + " " + cardinality + " " + fillerLabel;
            } else {
                definition = propLabel + " " + restrictionType + " " + fillerLabel;
            }
            axiom.put("id", restrictionNode);
            axiom.put("type", "SubClassOf");
            axiom.put("definition", definition);
            axiom.put("isRestriction", "true");
            axiom.put("propertyIri", propIri);
            axiom.put("restrictionType", restrictionType);
            axiom.put("fillerIri", fillerIri);
            if (!cardinality.isEmpty()) {
                axiom.put("cardinality", cardinality);
            }
            subClassAxioms.add(axiom);
        }
        
        // --- Process SubClassOf intersection ---
        TupleQueryResult intersectionRs = intersectionFuture.join();
        Map<String, List<String>> intersectionGroups = new LinkedHashMap<>();
        Map<String, List<String>> intersectionLabels = new LinkedHashMap<>();
        while (intersectionRs.hasNext()) {
            BindingSet sol = intersectionRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String memberIri = resource(sol, "member");
            String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(memberIri);
            if (memberIri != null) {
                intersectionGroups.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberIri);
                intersectionLabels.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberLabel);
            }
        }
        for (Map.Entry<String, List<String>> entry : intersectionGroups.entrySet()) {
            String bnode = entry.getKey();
            List<String> labels = intersectionLabels.get(bnode);
            if (labels != null && !labels.isEmpty()) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", bnode);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", String.join(" and ", labels));
                axiom.put("isComplex", "true");
                axiom.put("expressionType", "intersection");
                subClassAxioms.add(axiom);
            }
        }
        
        // --- Process SubClassOf union ---
        TupleQueryResult unionRs = unionFuture.join();
        Map<String, List<String>> unionGroups = new LinkedHashMap<>();
        Map<String, List<String>> unionLabels = new LinkedHashMap<>();
        while (unionRs.hasNext()) {
            BindingSet sol = unionRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String memberIri = resource(sol, "member");
            String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(memberIri);
            if (memberIri != null) {
                unionGroups.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberIri);
                unionLabels.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberLabel);
            }
        }
        for (Map.Entry<String, List<String>> entry : unionGroups.entrySet()) {
            String bnode = entry.getKey();
            List<String> labels = unionLabels.get(bnode);
            if (labels != null && !labels.isEmpty()) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", bnode);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", String.join(" or ", labels));
                axiom.put("isComplex", "true");
                axiom.put("expressionType", "union");
                subClassAxioms.add(axiom);
            }
        }
        
        // --- Process SubClassOf complement ---
        TupleQueryResult complementRs = complementFuture.join();
        while (complementRs.hasNext()) {
            BindingSet sol = complementRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String complementIri = resource(sol, "complement");
            String complementLabel = sol.hasBinding("complementLabel") ? literal(sol, "complementLabel") : localName(complementIri);
            if (complementIri != null) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", bnode);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", "not " + complementLabel);
                axiom.put("isComplex", "true");
                axiom.put("expressionType", "complement");
                subClassAxioms.add(axiom);
            }
        }
        
        details.put("subClassOfAxioms", subClassAxioms);
        
        // --- Process EquivalentClass simple ---
        List<Map<String, String>> equivAxioms = new ArrayList<>();
        TupleQueryResult equivRs = equivFuture.join();
        while (equivRs.hasNext()) {
            BindingSet sol = equivRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String equivIri = resource(sol, "equiv");
            if (equivIri != null) {
                axiom.put("id", equivIri);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(equivIri));
                equivAxioms.add(axiom);
            }
        }
        
        // --- Process EquivalentClass restrictions ---
        TupleQueryResult equivRestrictionRs = equivRestrictionFuture.join();
        while (equivRestrictionRs.hasNext()) {
            BindingSet sol = equivRestrictionRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String restrictionNode = sol.getValue("restriction").stringValue();
            String propIri = resource(sol, "prop");
            String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : formatIriWithPrefix(propIri);
            String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
            String fillerIri = sol.hasBinding("filler") ? sol.getValue("filler").stringValue() : "";
            String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel") : formatIriWithPrefix(fillerIri);
            String cardinality = sol.hasBinding("card") ? literal(sol, "card") : "";
            String definition;
            if (!cardinality.isEmpty()) {
                definition = propLabel + " " + restrictionType + " " + cardinality + " " + fillerLabel;
            } else {
                definition = propLabel + " " + restrictionType + " " + fillerLabel;
            }
            axiom.put("id", restrictionNode);
            axiom.put("type", "EquivalentTo");
            axiom.put("definition", definition);
            axiom.put("isRestriction", "true");
            axiom.put("propertyIri", propIri);
            axiom.put("restrictionType", restrictionType);
            axiom.put("fillerIri", fillerIri);
            if (!cardinality.isEmpty()) {
                axiom.put("cardinality", cardinality);
            }
            equivAxioms.add(axiom);
        }
        
        // --- Process EquivalentClass intersection ---
        TupleQueryResult equivIntersectionRs = equivIntersectionFuture.join();
        Map<String, List<String>> equivIntersectionGroups = new LinkedHashMap<>();
        Map<String, List<String>> equivIntersectionLabels = new LinkedHashMap<>();
        while (equivIntersectionRs.hasNext()) {
            BindingSet sol = equivIntersectionRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String memberIri = resource(sol, "member");
            String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(memberIri);
            if (memberIri != null) {
                equivIntersectionGroups.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberIri);
                equivIntersectionLabels.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberLabel);
            }
        }
        for (Map.Entry<String, List<String>> entry : equivIntersectionGroups.entrySet()) {
            String bnode = entry.getKey();
            List<String> labels = equivIntersectionLabels.get(bnode);
            if (labels != null && !labels.isEmpty()) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", bnode);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", String.join(" and ", labels));
                axiom.put("isComplex", "true");
                axiom.put("expressionType", "intersection");
                equivAxioms.add(axiom);
            }
        }
        
        // --- Process EquivalentClass union ---
        TupleQueryResult equivUnionRs = equivUnionFuture.join();
        Map<String, List<String>> equivUnionGroups = new LinkedHashMap<>();
        Map<String, List<String>> equivUnionLabels = new LinkedHashMap<>();
        while (equivUnionRs.hasNext()) {
            BindingSet sol = equivUnionRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String memberIri = resource(sol, "member");
            String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(memberIri);
            if (memberIri != null) {
                equivUnionGroups.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberIri);
                equivUnionLabels.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberLabel);
            }
        }
        for (Map.Entry<String, List<String>> entry : equivUnionGroups.entrySet()) {
            String bnode = entry.getKey();
            List<String> labels = equivUnionLabels.get(bnode);
            if (labels != null && !labels.isEmpty()) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", bnode);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", String.join(" or ", labels));
                axiom.put("isComplex", "true");
                axiom.put("expressionType", "union");
                equivAxioms.add(axiom);
            }
        }
        
        // --- Process EquivalentClass complement ---
        TupleQueryResult equivComplementRs = equivComplementFuture.join();
        while (equivComplementRs.hasNext()) {
            BindingSet sol = equivComplementRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String complementIri = resource(sol, "complement");
            String complementLabel = sol.hasBinding("complementLabel") ? literal(sol, "complementLabel") : localName(complementIri);
            if (complementIri != null) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", bnode);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", "not " + complementLabel);
                axiom.put("isComplex", "true");
                axiom.put("expressionType", "complement");
                equivAxioms.add(axiom);
            }
        }
        
        // --- Process EquivalentClass oneOf ---
        TupleQueryResult equivOneOfRs = equivOneOfFuture.join();
        Map<String, List<String>> equivOneOfGroups = new LinkedHashMap<>();
        Map<String, List<String>> equivOneOfLabels = new LinkedHashMap<>();
        while (equivOneOfRs.hasNext()) {
            BindingSet sol = equivOneOfRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String indIri = resource(sol, "individual");
            String indLabel = sol.hasBinding("indLabel") ? literal(sol, "indLabel") : localName(indIri != null ? indIri : "");
            if (indIri != null) {
                equivOneOfGroups.computeIfAbsent(bnode, k -> new ArrayList<>()).add(indIri);
                equivOneOfLabels.computeIfAbsent(bnode, k -> new ArrayList<>()).add(indLabel);
            }
        }
        for (Map.Entry<String, List<String>> entry : equivOneOfGroups.entrySet()) {
            String bnode = entry.getKey();
            List<String> labels = equivOneOfLabels.get(bnode);
            if (labels != null && !labels.isEmpty()) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", bnode);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", "{" + String.join(", ", labels) + "}");
                axiom.put("isComplex", "true");
                axiom.put("expressionType", "oneOf");
                equivAxioms.add(axiom);
            }
        }
        
        details.put("equivalentClassesAxioms", equivAxioms);
        
        // --- Process DisjointWith ---
        TupleQueryResult disjointRs = disjointFuture.join();
        List<Map<String, String>> disjointAxioms = new ArrayList<>();
        while (disjointRs.hasNext()) {
            BindingSet sol = disjointRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                axiom.put("id", disjointIri);
                axiom.put("type", "DisjointWith");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                disjointAxioms.add(axiom);
            }
        }
        details.put("disjointClassesAxioms", disjointAxioms);
        
        // --- Process DisjointUnionOf ---
        TupleQueryResult disjointUnionRs = disjointUnionFuture.join();
        Map<String, List<String>> disjointUnionGroups = new LinkedHashMap<>();
        while (disjointUnionRs.hasNext()) {
            BindingSet sol = disjointUnionRs.next();
            String listNode = sol.getValue("list").stringValue();
            String memberIri = sol.getValue("member").stringValue();
            disjointUnionGroups.computeIfAbsent(listNode, k -> new ArrayList<>()).add(memberIri);
        }
        List<Map<String, Object>> disjointUnionAxioms = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : disjointUnionGroups.entrySet()) {
            String listNode = entry.getKey();
            List<String> members = entry.getValue();
            if (!members.isEmpty()) {
                Map<String, Object> axiom = new LinkedHashMap<>();
                axiom.put("id", listNode);
                axiom.put("type", "DisjointUnionOf");
                axiom.put("members", members);
                StringBuilder defBuilder = new StringBuilder();
                for (int i = 0; i < members.size(); i++) {
                    if (i > 0) defBuilder.append(", ");
                    defBuilder.append(localName(members.get(i)));
                }
                axiom.put("definition", defBuilder.toString());
                disjointUnionAxioms.add(axiom);
            }
        }
        details.put("disjointUnionAxioms", disjointUnionAxioms);
        
        // --- Process HasKey ---
        TupleQueryResult hasKeyRs = hasKeyFuture.join();
        Map<String, List<String>> hasKeyGroups = new LinkedHashMap<>();
        while (hasKeyRs.hasNext()) {
            BindingSet sol = hasKeyRs.next();
            String listNode = sol.getValue("keyList").stringValue();
            String propIri = sol.getValue("prop").stringValue();
            hasKeyGroups.computeIfAbsent(listNode, k -> new ArrayList<>()).add(propIri);
        }
        List<Map<String, Object>> hasKeyAxioms = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : hasKeyGroups.entrySet()) {
            String listNode = entry.getKey();
            List<String> keyProperties = entry.getValue();
            if (!keyProperties.isEmpty()) {
                Map<String, Object> axiom = new LinkedHashMap<>();
                axiom.put("id", listNode);
                axiom.put("type", "HasKey");
                axiom.put("properties", keyProperties);
                StringBuilder defBuilder = new StringBuilder();
                for (int i = 0; i < keyProperties.size(); i++) {
                    if (i > 0) defBuilder.append(", ");
                    defBuilder.append(localName(keyProperties.get(i)));
                }
                axiom.put("definition", defBuilder.toString());
                hasKeyAxioms.add(axiom);
            }
        }
        details.put("hasKeyAxioms", hasKeyAxioms);
        
        // --- Process inferred equivalent classes ---
        TupleQueryResult inferredEquivRs = inferredEquivFuture.join();
        List<Map<String, String>> inferredEquivAxioms = new ArrayList<>();
        while (inferredEquivRs.hasNext()) {
            BindingSet sol = inferredEquivRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String equivIri = resource(sol, "equiv");
            if (equivIri != null) {
                axiom.put("id", equivIri);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(equivIri));
                axiom.put("isInferred", "true");
                inferredEquivAxioms.add(axiom);
            }
        }
        details.put("inferredEquivalentClassesAxioms", inferredEquivAxioms);
        
        // --- Process inferred superclasses ---
        TupleQueryResult inferredSuperRs = inferredSuperFuture.join();
        List<Map<String, String>> inferredSubClassAxioms = new ArrayList<>();
        while (inferredSuperRs.hasNext()) {
            BindingSet sol = inferredSuperRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String superIri = resource(sol, "super");
            if (superIri != null) {
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                axiom.put("isInferred", "true");
                inferredSubClassAxioms.add(axiom);
            }
        }
        details.put("inferredSubClassOfAxioms", inferredSubClassAxioms);
        
        // --- Process inferred disjoint classes ---
        TupleQueryResult inferredDisjointRs = inferredDisjointFuture.join();
        List<Map<String, String>> inferredDisjointAxioms = new ArrayList<>();
        while (inferredDisjointRs.hasNext()) {
            BindingSet sol = inferredDisjointRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                axiom.put("id", disjointIri);
                axiom.put("type", "DisjointWith");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                axiom.put("isInferred", "true");
                inferredDisjointAxioms.add(axiom);
            }
        }
        details.put("inferredDisjointClassesAxioms", inferredDisjointAxioms);
        
        // --- Process GCI axioms ---
        TupleQueryResult gciRs = gciFuture.join();
        List<Map<String, String>> generalClassAxioms = new ArrayList<>();
        while (gciRs.hasNext()) {
            BindingSet sol = gciRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String subExpr = sol.getValue("subExpr").stringValue();
            String superClass = resource(sol, "superClass");
            axiom.put("id", subExpr);
            axiom.put("type", "GCI");
            axiom.put("definition", "Complex axiom involving " + localName(classIri));
            generalClassAxioms.add(axiom);
        }
        details.put("generalClassAxioms", generalClassAxioms);
        
        // --- Process ancestor axioms ---
        TupleQueryResult ancestorRs = ancestorFuture.join();
        List<Map<String, String>> anonymousAncestorAxioms = new ArrayList<>();
        Set<String> seenAncestors = new LinkedHashSet<>();
        while (ancestorRs.hasNext()) {
            BindingSet sol = ancestorRs.next();
            String superIri = sol.getValue("super").stringValue();
            if (!seenAncestors.contains(superIri)) {
                seenAncestors.add(superIri);
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                if (superIri.startsWith("_:")) {
                    axiom.put("definition", "Anonymous superclass");
                } else {
                    axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                }
                anonymousAncestorAxioms.add(axiom);
            }
        }
        details.put("anonymousAncestorAxioms", anonymousAncestorAxioms);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] classDetails for {} completed in {}ms project={} (parallel)", localName(classIri), duration, projectId);
        return details;
    }

    /**
     * Get all instances (individuals) of a given class.
     * Returns both asserted and inferred instances.
     * OPTIMIZED: Cached + combined into single SPARQL query with BIND for isInferred flag.
     */
    @Cacheable(value = "classInstances", key = "#projectId + '_' + #classIri")
    public List<Map<String, Object>> getClassInstances(String projectId, String classIri) {
        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> instances = new ArrayList<>();
        Set<String> seenIndividuals = new LinkedHashSet<>();
        
        // OPTIMIZED: Single query that returns both asserted and inferred instances
        // Uses BIND to flag inferred instances instead of two separate queries
        String combinedQuery = PREFIXES + """
            SELECT DISTINCT ?individual ?label ?isInferred WHERE {
              {
                ?individual a <%s> .
                BIND(false AS ?isInferred)
              } UNION {
                GRAPH <http://www.ontotext.com/inferred> {
                  ?individual a <%s> .
                }
                FILTER NOT EXISTS {
                  GRAPH <http://www.ontotext.com/explicit> {
                    ?individual a <%s> .
                  }
                }
                BIND(true AS ?isInferred)
              }
              OPTIONAL { ?individual rdfs:label ?label }
            }
            ORDER BY ?label
            """.formatted(classIri, classIri, classIri);
        
        TupleQueryResult rs = datasetService.execSelect(projectId, combinedQuery);
        
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String individualIri = resource(sol, "individual");
            if (individualIri != null && !seenIndividuals.contains(individualIri)) {
                seenIndividuals.add(individualIri);
                Map<String, Object> individual = new LinkedHashMap<>();
                individual.put("id", individualIri);
                individual.put("label", sol.hasBinding("label") ? literal(sol, "label") : localName(individualIri));
                boolean inferred = sol.hasBinding("isInferred") && "true".equals(sol.getValue("isInferred").stringValue());
                individual.put("isInferred", inferred);
                
                List<String> types = new ArrayList<>();
                types.add(classIri);
                individual.put("types", types);
                
                instances.add(individual);
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] getClassInstances for {} loaded {} instances in {}ms project={}", localName(classIri), instances.size(), duration, projectId);
        return instances;
    }

    /**
     * Get per-class instance counts (asserted and inferred).
     */
    /**
     * Get per-class instance counts.
     * OPTIMIZED: Cached + simplified query (skip inferred graph for speed).
     */
    @Cacheable(value = "classInstanceCounts", key = "#projectId")
    public Map<String, Map<String, Integer>> getClassInstanceCounts(String projectId) {
        long startTime = System.currentTimeMillis();
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();

        // OPTIMIZED: Single simple query instead of querying explicit/inferred graphs separately
        // The explicit/inferred graph split is a GraphDB-specific feature that's very slow on large ontologies
        String query = PREFIXES + """
            SELECT ?class (COUNT(DISTINCT ?individual) AS ?count) WHERE {
              ?individual a ?class .
              FILTER(isIRI(?class))
              FILTER(?class != owl:NamedIndividual)
              FILTER(?class != owl:Class)
              FILTER(?class != owl:Thing)
            }
            GROUP BY ?class
            HAVING (COUNT(DISTINCT ?individual) > 0)
            """;
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String classIri = resource(sol, "class");
                if (classIri != null) {
                    int cnt = literalToInt(sol, "count");
                    Map<String, Integer> entry = new LinkedHashMap<>();
                    entry.put("direct", cnt);
                    entry.put("inferred", 0);
                    entry.put("total", cnt);
                    counts.put(classIri, entry);
                }
            }
        } catch (Exception e) {
            log.warn("[PERF] Instance counts query failed (non-critical): {}", e.getMessage());
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded instance counts for {} classes in {}ms", counts.size(), duration);
        return counts;
    }

    /**
     * Get detailed information about an individual
     */
    public Map<String, Object> getIndividualDetails(String projectId, String individualIri) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", individualIri);
        
        // Get label
        String labelQuery = PREFIXES + """
            SELECT ?label WHERE {
              <%s> rdfs:label ?label
            } LIMIT 1
            """.formatted(individualIri);
        TupleQueryResult labelRs = datasetService.execSelect(projectId, labelQuery);
        if (labelRs.hasNext()) {
            BindingSet labelSol = labelRs.next();
            details.put("label", literal(labelSol, "label"));
        } else {
            details.put("label", localName(individualIri));
        }
        
        // Get types
        String typesQuery = PREFIXES + """
            SELECT DISTINCT ?type ?typeLabel WHERE {
              <%s> a ?type .
              FILTER(isIRI(?type) && ?type != owl:NamedIndividual)
              OPTIONAL { ?type rdfs:label ?typeLabel }
            }
            """.formatted(individualIri);
        TupleQueryResult typesRs = datasetService.execSelect(projectId, typesQuery);
        List<String> types = new ArrayList<>();
        while (typesRs.hasNext()) {
            BindingSet sol = typesRs.next();
            String typeIri = resource(sol, "type");
            if (typeIri != null) {
                types.add(typeIri);
            }
        }
        details.put("types", types);
        
        // Get annotations
        String annQuery = PREFIXES + """
            SELECT ?prop ?value WHERE {
              <%s> ?prop ?value .
              FILTER(isLiteral(?value))
              {
                ?prop a owl:AnnotationProperty .
              } UNION {
                VALUES ?prop { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
              }
            }
            """.formatted(individualIri);
        TupleQueryResult annRs = datasetService.execSelect(projectId, annQuery);
        Map<String, Object> annotations = new LinkedHashMap<>();
        while (annRs.hasNext()) {
            BindingSet sol = annRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null && sol.hasBinding("value")) {
                String value = sol.getValue("value").stringValue();
                annotations.put(propIri, value);
            }
        }
        details.put("annotations", annotations);
        
        // Get property assertions
        String propsQuery = PREFIXES + """
            SELECT ?prop ?obj ?objLabel WHERE {
              <%s> ?prop ?obj .
              FILTER(?prop != rdf:type)
              FILTER(?prop != owl:sameAs)
              FILTER(?prop != owl:differentFrom)
              FILTER NOT EXISTS { ?prop a owl:AnnotationProperty }
              OPTIONAL { ?obj rdfs:label ?objLabel }
            }
            """.formatted(individualIri);
        TupleQueryResult propsRs = datasetService.execSelect(projectId, propsQuery);
        List<Map<String, Object>> propertyAssertions = new ArrayList<>();
        while (propsRs.hasNext()) {
            BindingSet sol = propsRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                Map<String, Object> assertion = new LinkedHashMap<>();
                assertion.put("id", "assertion-" + propertyAssertions.size());
                assertion.put("propertyIri", propIri);
                assertion.put("propertyLabel", localName(propIri));
                
                Value objValue = sol.getValue("obj");
                if (objValue.isIRI()) {
                    assertion.put("targetIri", objValue.stringValue());
                    assertion.put("targetLabel", sol.hasBinding("objLabel") ? literal(sol, "objLabel") : localName(objValue.stringValue()));
                    assertion.put("isObjectProperty", true);
                } else {
                    assertion.put("targetLiteral", objValue.stringValue());
                    assertion.put("isObjectProperty", false);
                }
                
                propertyAssertions.add(assertion);
            }
        }

        // Get negative property assertions (OWL2 NegativePropertyAssertion)
        String negativePropsQuery = PREFIXES + """
            SELECT ?prop ?targetInd ?targetIndLabel ?targetValue WHERE {
              ?npa a owl:NegativePropertyAssertion ;
                   owl:sourceIndividual <%s> ;
                   owl:assertionProperty ?prop .
              OPTIONAL {
                ?npa owl:targetIndividual ?targetInd .
                OPTIONAL { ?targetInd rdfs:label ?targetIndLabel }
              }
              OPTIONAL { ?npa owl:targetValue ?targetValue . }
            }
            """.formatted(individualIri);
        TupleQueryResult negRs = datasetService.execSelect(projectId, negativePropsQuery);
        while (negRs.hasNext()) {
            BindingSet sol = negRs.next();
            String propIri = resource(sol, "prop");
            if (propIri == null) continue;

            Map<String, Object> assertion = new LinkedHashMap<>();
            assertion.put("id", "neg-assertion-" + propertyAssertions.size());
            assertion.put("propertyIri", propIri);
            assertion.put("propertyLabel", localName(propIri));
            assertion.put("isNegative", true);

            Value targetInd = sol.getValue("targetInd");
            Value targetValue = sol.getValue("targetValue");
            if (targetInd != null && targetInd.isIRI()) {
                assertion.put("targetIri", targetInd.stringValue());
                assertion.put("targetLabel", sol.hasBinding("targetIndLabel") ? literal(sol, "targetIndLabel") : localName(targetInd.stringValue()));
                assertion.put("isObjectProperty", true);
            } else if (targetValue != null) {
                assertion.put("targetLiteral", targetValue.stringValue());
                assertion.put("isObjectProperty", false);
            } else {
                // Skip malformed NPA without a target
                continue;
            }

            propertyAssertions.add(assertion);
        }
        details.put("propertyAssertions", propertyAssertions);
        
        // Get sameAs
        String sameAsQuery = PREFIXES + """
            SELECT ?same WHERE {
              <%s> owl:sameAs ?same .
            }
            """.formatted(individualIri);
        TupleQueryResult sameRs = datasetService.execSelect(projectId, sameAsQuery);
        List<String> sameAs = new ArrayList<>();
        while (sameRs.hasNext()) {
            BindingSet sol = sameRs.next();
            String same = resource(sol, "same");
            if (same != null) {
                sameAs.add(same);
            }
        }
        details.put("sameIndividualAs", sameAs);
        
        // Get differentFrom
        String diffQuery = PREFIXES + """
            SELECT ?diff WHERE {
              <%s> owl:differentFrom ?diff .
            }
            """.formatted(individualIri);
        TupleQueryResult diffRs = datasetService.execSelect(projectId, diffQuery);
        List<String> differentFrom = new ArrayList<>();
        while (diffRs.hasNext()) {
            BindingSet sol = diffRs.next();
            String diff = resource(sol, "diff");
            if (diff != null) {
                differentFrom.add(diff);
            }
        }
        details.put("differentIndividualFrom", differentFrom);
        
        return details;
    }

    public Map<String, Object> getOntologySchema(String projectId) {
        Map<String, Object> schema = new LinkedHashMap<>();
        
        // Get all classes
        String classesQuery = PREFIXES + """
            SELECT DISTINCT ?class WHERE {
              ?class a owl:Class .
              FILTER(isIRI(?class))
              FILTER(?class != owl:Thing && ?class != owl:Nothing)
            }
            ORDER BY ?class
            LIMIT 1000
            """;
        TupleQueryResult classesResult = datasetService.execSelect(projectId, classesQuery);
        List<String> classes = new ArrayList<>();
        while (classesResult.hasNext()) {
            BindingSet sol = classesResult.next();
            String cls = resource(sol, "class");
            if (cls != null) {
                classes.add(cls);
            }
        }
        schema.put("classes", classes);
        
        // Get all object properties
        String objectPropsQuery = PREFIXES + """
            SELECT DISTINCT ?prop WHERE {
              ?prop a owl:ObjectProperty .
              FILTER(isIRI(?prop))
            }
            ORDER BY ?prop
            LIMIT 1000
            """;
        TupleQueryResult objResult = datasetService.execSelect(projectId, objectPropsQuery);
        List<String> objectProperties = new ArrayList<>();
        while (objResult.hasNext()) {
            BindingSet sol = objResult.next();
            String prop = resource(sol, "prop");
            if (prop != null) {
                objectProperties.add(prop);
            }
        }
        schema.put("objectProperties", objectProperties);
        
        // Get all data properties
        String dataPropsQuery = PREFIXES + """
            SELECT DISTINCT ?prop WHERE {
              ?prop a owl:DatatypeProperty .
              FILTER(isIRI(?prop))
            }
            ORDER BY ?prop
            LIMIT 1000
            """;
        TupleQueryResult dataResult = datasetService.execSelect(projectId, dataPropsQuery);
        List<String> dataProperties = new ArrayList<>();
        while (dataResult.hasNext()) {
            BindingSet sol = dataResult.next();
            String prop = resource(sol, "prop");
            if (prop != null) {
                dataProperties.add(prop);
            }
        }
        schema.put("dataProperties", dataProperties);
        
        return schema;
    }
}


