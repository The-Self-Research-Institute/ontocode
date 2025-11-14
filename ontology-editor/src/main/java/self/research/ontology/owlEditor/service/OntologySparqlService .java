package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.config.SparqlProperties;
import self.research.ontology.owlEditor.dto.OntologyDtos.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OntologySparqlService {
    
    private static final Logger log = LoggerFactory.getLogger(OntologySparqlService.class);
    
    private final WebClient webClient;
    private final SparqlProperties props;
    private final ObjectMapper om;

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        """;

    public OntologySparqlService(WebClient.Builder builder, 
                                SparqlProperties props, 
                                ObjectMapper om) {
        this.props = props;
        this.om = om;
        
        WebClient.Builder webClientBuilder = builder.baseUrl(props.getEndpointUrl());
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            webClientBuilder.defaultHeaders(headers -> 
                headers.setBasicAuth(props.getUsername(), props.getPassword())
            );
        }
        this.webClient = webClientBuilder.build();
    }

    // Helper to execute SPARQL query
    public Mono<JsonNode> executeSparqlQuery(String query) {
        log.debug("Executing SPARQL query:\n{}", query);
        return webClient.post()
                .uri("")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header(HttpHeaders.ACCEPT, "application/sparql-results+json")
                .bodyValue("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> {
                    try {
                        return Mono.just(om.readTree(response));
                    } catch (Exception e) {
                        log.error("Failed to parse SPARQL response", e);
                        return Mono.error(new RuntimeException("Failed to parse SPARQL response", e));
                    }
                })
                .doOnError(e -> log.error("SPARQL query failed", e))
                .onErrorResume(e -> Mono.just(om.createObjectNode()));
    }

    // Helper methods
    public String graph(String projectId) { 
        return props.getProjectGraphUri(projectId); 
    }
    
    public String val(JsonNode binding, String key) { 
        return binding.has(key) && binding.get(key).has("value") 
            ? binding.get(key).get("value").asText() 
            : ""; 
    }
    
    private boolean bool(JsonNode binding, String key) { 
        return binding.has(key) && binding.get(key).has("value") 
            && binding.get(key).get("value").asBoolean(false); 
    }
    
    public int intval(JsonNode binding, String key) { 
        return binding.has(key) && binding.get(key).has("value") 
            ? binding.get(key).get("value").asInt(0) 
            : 0; 
    }
    
    private String local(String iri) {
        if (iri == null || iri.isEmpty()) return "";
        int i = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return i >= 0 && i < iri.length() - 1 ? iri.substring(i + 1) : iri;
    }
    
    private List<String> split(String s) {
        return (s == null || s.isBlank()) 
            ? List.of() 
            : Arrays.stream(s.split("\\|"))
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
    }

    // ========== READ OPERATIONS ==========

    public Mono<OntologyMetadataDto> getMetadata(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT
              (SAMPLE(?o) AS ?ontologyIRI)
              (SAMPLE(?v) AS ?versionIRISample)
              (COUNT(DISTINCT ?c) AS ?classCount)
              (COUNT(DISTINCT ?op) AS ?objPropCount)
              (COUNT(DISTINCT ?dp) AS ?dataPropCount)
              (COUNT(DISTINCT ?i) AS ?individualCount)
              (COUNT(DISTINCT ?ap) AS ?annPropCount)
            WHERE {
              GRAPH <%s> {
                OPTIONAL { ?o a owl:Ontology . OPTIONAL { ?o owl:versionIRI ?v } }
                OPTIONAL { ?c a owl:Class . FILTER(isIRI(?c) && ?c != owl:Thing) }
                OPTIONAL { ?op a owl:ObjectProperty . FILTER(!isBlank(?op)) }
                OPTIONAL { ?dp a owl:DatatypeProperty . FILTER(!isBlank(?dp)) }
                OPTIONAL { ?i a owl:NamedIndividual }
                OPTIONAL { 
                    ?ap a owl:AnnotationProperty . 
                    FILTER(!isBlank(?ap) && ?ap != rdfs:label && ?ap != rdfs:comment) 
                }
              }
            }
        """, g);

        return executeSparqlQuery(query).map(json -> {
            OntologyMetadataDto metadata = new OntologyMetadataDto();
            JsonNode bindings = json.path("results").path("bindings");
            if (bindings.size() > 0) {
                JsonNode row = bindings.get(0);
                metadata.setOntologyIRI(val(row, "ontologyIRI"));
                metadata.setVersionIRI(val(row, "versionIRISample"));
                metadata.setClassCount(intval(row, "classCount"));
                metadata.setObjectPropertyCount(intval(row, "objPropCount"));
                metadata.setDataPropertyCount(intval(row, "dataPropCount"));
                metadata.setIndividualCount(intval(row, "individualCount"));
                metadata.setAnnotationPropertyCount(intval(row, "annPropCount"));
            }
            return metadata;
        });
    }

    public Mono<List<TreeNode>> getTopLevelClasses(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT ?id (SAMPLE(?lbl) AS ?label)
                   (EXISTS { GRAPH <%s> { ?child rdfs:subClassOf ?id . FILTER(?child != ?id) } } AS ?hasChildren)
            WHERE { 
              GRAPH <%s> {
                ?id a owl:Class ;
                    rdfs:subClassOf owl:Thing .
                FILTER(isIRI(?id) && ?id != owl:Thing)
                OPTIONAL { ?id rdfs:label ?lbl }
              }
            } 
            GROUP BY ?id 
            ORDER BY LCASE(?label)
        """, g, g);

        return executeSparqlQuery(query).map(this::mapToTreeNodes);
    }

    public Mono<List<TreeNode>> getClassChildren(String projectId, String parentIri) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT ?id (SAMPLE(?lbl) AS ?label)
                   (EXISTS { GRAPH <%s> { ?child rdfs:subClassOf ?id . FILTER(?child != ?id) } } AS ?hasChildren)
            WHERE { 
              GRAPH <%s> {
                ?id rdfs:subClassOf <%s> .
                FILTER(isIRI(?id))
                OPTIONAL { ?id rdfs:label ?lbl }
              }
            } 
            GROUP BY ?id 
            ORDER BY LCASE(?label)
        """, g, g, parentIri);

        return executeSparqlQuery(query).map(this::mapToTreeNodes);
    }

    public Mono<List<TreeNodeWithParent>> getAllClassesWithParent(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT ?id (SAMPLE(?lbl) AS ?label) ?parent
            WHERE { 
              GRAPH <%s> {
                ?id a owl:Class .
                FILTER(isIRI(?id))
                OPTIONAL { ?id rdfs:label ?lbl }
                OPTIONAL { 
                    ?id rdfs:subClassOf ?parent . 
                    FILTER(isIRI(?parent)) 
                }
              }
            } 
            GROUP BY ?id ?parent 
            ORDER BY LCASE(?label)
        """, g);

        return executeSparqlQuery(query).map(this::mapToTreeNodesWithParent);
    }

    public Mono<List<PropertyDto>> getProperties(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT ?iri (SAMPLE(?lbl) AS ?label) ?type
                   (GROUP_CONCAT(DISTINCT ?domain; SEPARATOR="|") AS ?domains)
                   (GROUP_CONCAT(DISTINCT ?range; SEPARATOR="|") AS ?ranges)
            WHERE { 
              GRAPH <%s> {
                {
                    ?iri a owl:ObjectProperty .
                    BIND("ObjectProperty" AS ?type)
                    FILTER(!isBlank(?iri))
                }
                UNION
                {
                    ?iri a owl:DatatypeProperty .
                    BIND("DataProperty" AS ?type)
                    FILTER(!isBlank(?iri))
                }
                OPTIONAL { ?iri rdfs:label ?lbl }
                OPTIONAL { ?iri rdfs:domain ?domain . FILTER(isIRI(?domain)) }
                OPTIONAL { ?iri rdfs:range ?range . FILTER(isIRI(?range)) }
              }
            } 
            GROUP BY ?iri ?type 
            ORDER BY ?type LCASE(?label)
        """, g);

        return executeSparqlQuery(query).map(this::mapToProperties);
    }

    public Mono<List<IndividualDto>> getIndividuals(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT ?iri (SAMPLE(?lbl) AS ?label)
                   (GROUP_CONCAT(DISTINCT ?type; SEPARATOR="|") AS ?types)
            WHERE { 
              GRAPH <%s> {
                ?iri a owl:NamedIndividual ;
                     a ?type .
                FILTER(?type != owl:NamedIndividual && isIRI(?type))
                OPTIONAL { ?iri rdfs:label ?lbl }
              }
            } 
            GROUP BY ?iri 
            ORDER BY LCASE(?label)
        """, g);

        return executeSparqlQuery(query).map(this::mapToIndividuals);
    }

    public Mono<List<SimpleEntityDto>> getAnnotationProperties(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT DISTINCT ?iri (SAMPLE(?lbl) AS ?label)
            WHERE { 
              GRAPH <%s> {
                ?iri a owl:AnnotationProperty .
                FILTER(!isBlank(?iri) && ?iri != rdfs:label && ?iri != rdfs:comment)
                OPTIONAL { ?iri rdfs:label ?lbl }
              }
            } 
            GROUP BY ?iri 
            ORDER BY LCASE(?label)
        """, g);

        return executeSparqlQuery(query).map(this::mapToSimpleEntities);
    }

    public Mono<List<SimpleEntityDto>> getDatatypes(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT DISTINCT ?iri 
            WHERE { 
              GRAPH <%s> { 
                ?iri a rdfs:Datatype . 
                FILTER(isIRI(?iri)) 
              } 
            }
            ORDER BY ?iri
        """, g);

        return executeSparqlQuery(query).map(json -> {
            List<SimpleEntityDto> results = new ArrayList<>();
            JsonNode bindings = json.path("results").path("bindings");
            
            for (JsonNode binding : bindings) {
                String iri = val(binding, "iri");
                if (!iri.isEmpty()) {
                    SimpleEntityDto dto = new SimpleEntityDto();
                    dto.setIri(iri);
                    dto.setLabel(local(iri));
                    dto.setLocalName(local(iri));
                    results.add(dto);
                }
            }
            return results;
        });
    }

    public Mono<JsonNode> searchEntities(String projectId, String searchTerm, String entityType) {
        String g = graph(projectId);
        String typeFilter = switch(entityType) {
            case "class" -> "?entity a owl:Class .";
            case "property" -> "{ ?entity a owl:ObjectProperty } UNION { ?entity a owl:DatatypeProperty }";
            case "individual" -> "?entity a owl:NamedIndividual .";
            default -> "";
        };
        
        String query = PREFIXES + String.format("""
            SELECT DISTINCT ?entity (SAMPLE(?label) AS ?entityLabel) ?type
            WHERE { 
              GRAPH <%s> {
                %s
                ?entity a ?type .
                OPTIONAL { ?entity rdfs:label ?label }
                FILTER(
                    CONTAINS(LCASE(STR(?entity)), LCASE("%s")) ||
                    CONTAINS(LCASE(STR(?label)), LCASE("%s"))
                )
              }
            } 
            GROUP BY ?entity ?type
            LIMIT 50
        """, g, typeFilter, searchTerm, searchTerm);
        
        return executeSparqlQuery(query);
    }

    public Mono<List<Map<String,String>>> getNamespaces(String projectId) {
        String g = graph(projectId);
        String query = String.format("""
            SELECT DISTINCT ?namespace
            WHERE { 
              GRAPH <%s> {
                { ?s ?p ?o . BIND(REPLACE(STR(?s), "(#|/)[^#/]*$", "$1") AS ?namespace) }
                UNION
                { ?s ?p ?o . FILTER(isIRI(?o)) BIND(REPLACE(STR(?o), "(#|/)[^#/]*$", "$1") AS ?namespace) }
                UNION
                { ?s ?p ?o . FILTER(isIRI(?p)) BIND(REPLACE(STR(?p), "(#|/)[^#/]*$", "$1") AS ?namespace) }
              }
            } 
            ORDER BY ?namespace
        """, g);
        
        return executeSparqlQuery(query).map(json -> {
            List<Map<String,String>> result = new ArrayList<>();
            json.path("results").path("bindings").forEach(b -> {
                String ns = val(b, "namespace");
                if (!ns.isEmpty()) {
                    String prefix = ns.contains("#") 
                        ? ns.substring(ns.lastIndexOf('/') + 1, ns.length() - 1)
                        : ns.substring(ns.lastIndexOf('/', ns.length() - 2) + 1, ns.length() - 1);
                    result.add(Map.of("prefix", prefix, "namespace", ns));
                }
            });
            return result;
        });
    }

    public Mono<Map<String,Object>> getStatistics(String projectId) {
        String g = graph(projectId);
        String query = PREFIXES + String.format("""
            SELECT
              (COUNT(DISTINCT ?class) AS ?classes)
              (COUNT(DISTINCT ?objProp) AS ?objectProperties)
              (COUNT(DISTINCT ?dataProp) AS ?dataProperties)
              (COUNT(DISTINCT ?individual) AS ?individuals)
              (COUNT(DISTINCT ?subClassAxiom) AS ?subClassAxioms)
              (COUNT(DISTINCT ?assertion) AS ?propertyAssertions)
              (COUNT(DISTINCT ?annotation) AS ?annotations)
            WHERE {
              GRAPH <%s> {
                OPTIONAL { ?class a owl:Class . FILTER(isIRI(?class) && ?class != owl:Thing) }
                OPTIONAL { ?objProp a owl:ObjectProperty . FILTER(!isBlank(?objProp)) }
                OPTIONAL { ?dataProp a owl:DatatypeProperty . FILTER(!isBlank(?dataProp)) }
                OPTIONAL { ?individual a owl:NamedIndividual }
                OPTIONAL { 
                  ?c rdfs:subClassOf ?p . 
                  FILTER(isIRI(?c) && isIRI(?p))
                  BIND(?c AS ?subClassAxiom)
                }
                OPTIONAL {
                  ?s ?prop ?o .
                  FILTER(isIRI(?s) && isIRI(?prop) && ?prop != rdf:type)
                  BIND(?s AS ?assertion)
                }
                OPTIONAL {
                  ?entity ?annProp ?annValue .
                  FILTER(?annProp = rdfs:label || ?annProp = rdfs:comment)
                  BIND(?entity AS ?annotation)
                }
              }
            }
        """, g);
        
        return executeSparqlQuery(query).map(json -> {
            JsonNode b = json.path("results").path("bindings");
            if (b.size() == 0) return Map.of();
            JsonNode row = b.get(0);
            
            Map<String,Object> stats = new HashMap<>();
            stats.put("classes", intval(row, "classes"));
            stats.put("objectProperties", intval(row, "objectProperties"));
            stats.put("dataProperties", intval(row, "dataProperties"));
            stats.put("individuals", intval(row, "individuals"));
            stats.put("subClassAxioms", intval(row, "subClassAxioms"));
            stats.put("propertyAssertions", intval(row, "propertyAssertions"));
            stats.put("annotations", intval(row, "annotations"));
            
            int total = stats.values().stream()
                .filter(v -> v instanceof Integer)
                .mapToInt(v -> (Integer)v)
                .sum();
            stats.put("totalTriples", total);
            
            return stats;
        });
    }

    // ========== MAPPING HELPERS ==========

    private List<TreeNode> mapToTreeNodes(JsonNode jsonNode) {
        List<TreeNode> results = new ArrayList<>();
        JsonNode bindings = jsonNode.path("results").path("bindings");
        
        for (JsonNode binding : bindings) {
            String id = val(binding, "id");
            if (!id.isEmpty()) {
                TreeNode node = new TreeNode();
                node.setId(id);
                String label = val(binding, "label");
                node.setLabel(label.isEmpty() ? local(id) : label);
                node.setHasChildren(bool(binding, "hasChildren"));
                results.add(node);
            }
        }
        return results;
    }

    private List<TreeNodeWithParent> mapToTreeNodesWithParent(JsonNode jsonNode) {
        List<TreeNodeWithParent> results = new ArrayList<>();
        JsonNode bindings = jsonNode.path("results").path("bindings");
        
        for (JsonNode binding : bindings) {
            String id = val(binding, "id");
            if (!id.isEmpty()) {
                TreeNodeWithParent node = new TreeNodeWithParent();
                node.setId(id);
                String label = val(binding, "label");
                node.setLabel(label.isEmpty() ? local(id) : label);
                
                String parent = val(binding, "parent");
                if (!parent.isEmpty()) {
                    node.setParent(parent);
                }
                results.add(node);
            }
        }
        return results;
    }

    private List<PropertyDto> mapToProperties(JsonNode jsonNode) {
        List<PropertyDto> results = new ArrayList<>();
        JsonNode bindings = jsonNode.path("results").path("bindings");
        
        for (JsonNode binding : bindings) {
            String iri = val(binding, "iri");
            if (!iri.isEmpty()) {
                PropertyDto prop = new PropertyDto();
                prop.setIri(iri);
                String label = val(binding, "label");
                prop.setLabel(label.isEmpty() ? local(iri) : label);
                prop.setLocalName(local(iri));
                prop.setType(val(binding, "type"));
                prop.setDomains(split(val(binding, "domains")));
                prop.setRanges(split(val(binding, "ranges")));
                results.add(prop);
            }
        }
        return results;
    }

    private List<IndividualDto> mapToIndividuals(JsonNode jsonNode) {
        List<IndividualDto> results = new ArrayList<>();
        JsonNode bindings = jsonNode.path("results").path("bindings");
        
        for (JsonNode binding : bindings) {
            String iri = val(binding, "iri");
            if (!iri.isEmpty()) {
                IndividualDto ind = new IndividualDto();
                ind.setIri(iri);
                String label = val(binding, "label");
                ind.setLabel(label.isEmpty() ? local(iri) : label);
                ind.setLocalName(local(iri));
                ind.setTypes(split(val(binding, "types")));
                results.add(ind);
            }
        }
        return results;
    }

    private List<SimpleEntityDto> mapToSimpleEntities(JsonNode jsonNode) {
        List<SimpleEntityDto> results = new ArrayList<>();
        JsonNode bindings = jsonNode.path("results").path("bindings");
        
        for (JsonNode binding : bindings) {
            String iri = val(binding, "iri");
            if (!iri.isEmpty()) {
                SimpleEntityDto entity = new SimpleEntityDto();
                entity.setIri(iri);
                String label = val(binding, "label");
                entity.setLabel(label.isEmpty() ? local(iri) : label);
                entity.setLocalName(local(iri));
                results.add(entity);
            }
        }
        return results;
    }
}