package self.research.ontology.owlEditor.service;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing embedded Apache Jena TDB2 datasets.
 * Provides SPARQL query/update and bulk loading capabilities for large ontologies.
 * 
 * Each project gets its own TDB2 dataset in: data/projects/{projectId}/tdb2/
 */
@Service
public class Tdb2DatasetService {
    
    private static final Logger log = LoggerFactory.getLogger(Tdb2DatasetService.class);
    
    @Value("${ontocode.data.dir:./data}")
    private String dataDir;
    
    // Cache of open datasets (projectId -> Dataset)
    private final Map<String, Dataset> datasetCache = new ConcurrentHashMap<>();
    
    /**
     * Get or create TDB2 dataset for a project
     */
    public Dataset getDataset(String projectId) {
        return datasetCache.computeIfAbsent(projectId, this::createDataset);
    }
    
    /**
     * Create a new TDB2 dataset for a project
     */
    private Dataset createDataset(String projectId) {
        Path tdb2Path = getTdb2Path(projectId);
        
        try {
            // Create directory if it doesn't exist
            Files.createDirectories(tdb2Path);
            
            log.info("Opening TDB2 dataset for project: {} at {}", projectId, tdb2Path);
            
            // Create TDB2 dataset
            Dataset dataset = TDB2Factory.connectDataset(tdb2Path.toString());
            
            log.info("Successfully opened TDB2 dataset for project: {}", projectId);
            return dataset;
            
        } catch (Exception e) {
            log.error("Failed to create TDB2 dataset for project: {}", projectId, e);
            throw new RuntimeException("Failed to create TDB2 dataset", e);
        }
    }
    
    /**
     * Get the TDB2 directory path for a project
     */
    public Path getTdb2Path(String projectId) {
        return Paths.get(dataDir, "projects", projectId, "tdb2");
    }
    
    /**
     * Get the project directory path
     */
    public Path getProjectPath(String projectId) {
        return Paths.get(dataDir, "projects", projectId);
    }
    
    /**
     * Execute a SPARQL SELECT query
     */
    public ResultSet execSelect(String projectId, String sparqlQuery) {
        Dataset dataset = getDataset(projectId);
        
        try {
            dataset.begin(ReadWrite.READ);
            
            // Log dataset size
            long tripleCount = dataset.getDefaultModel().size();
            System.out.println("=== DATASET HAS " + tripleCount + " TRIPLES ===");
            
            Query query = QueryFactory.create(sparqlQuery);
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                // Important: materialize results before closing the transaction
                ResultSet results = qexec.execSelect();
                ResultSet materialized = ResultSetFactory.copyResults(results);
                dataset.commit();
                return materialized;
            }
            
        } catch (Exception e) {
            dataset.abort();
            log.error("SPARQL SELECT query failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL query execution failed", e);
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Execute a SPARQL CONSTRUCT query
     */
    public Model execConstruct(String projectId, String sparqlQuery) {
        Dataset dataset = getDataset(projectId);
        
        try {
            dataset.begin(ReadWrite.READ);
            
            Query query = QueryFactory.create(sparqlQuery);
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                Model model = qexec.execConstruct();
                dataset.commit();
                return model;
            }
            
        } catch (Exception e) {
            dataset.abort();
            log.error("SPARQL CONSTRUCT query failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL CONSTRUCT execution failed", e);
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Execute a SPARQL ASK query
     */
    public boolean execAsk(String projectId, String sparqlQuery) {
        Dataset dataset = getDataset(projectId);
        
        try {
            dataset.begin(ReadWrite.READ);
            
            Query query = QueryFactory.create(sparqlQuery);
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                boolean result = qexec.execAsk();
                dataset.commit();
                return result;
            }
            
        } catch (Exception e) {
            dataset.abort();
            log.error("SPARQL ASK query failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL ASK execution failed", e);
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Execute a SPARQL UPDATE operation
     */
    public void execUpdate(String projectId, String sparqlUpdate) {
        Dataset dataset = getDataset(projectId);
        
        try {
            dataset.begin(ReadWrite.WRITE);
            
            UpdateRequest updateRequest = UpdateFactory.create(sparqlUpdate);
            UpdateAction.execute(updateRequest, dataset);
            
            dataset.commit();
            log.debug("SPARQL UPDATE executed successfully for project: {}", projectId);
            
        } catch (Exception e) {
            dataset.abort();
            log.error("SPARQL UPDATE failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL UPDATE execution failed", e);
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Bulk load RDF data from file into TDB2
     * Supports: RDF/XML, Turtle, N-Triples, JSON-LD
     */
    public void bulkLoad(String projectId, InputStream inputStream, Lang rdfLang) {
        Dataset dataset = getDataset(projectId);
        
        log.info("Starting bulk load for project: {} with format: {}", projectId, rdfLang);
        
        try {
            dataset.begin(ReadWrite.WRITE);
            
            Model defaultModel = dataset.getDefaultModel();
            defaultModel.removeAll();
            
            // Use Jena RIOT streaming parser for large files
            RDFParser.source(inputStream)
                    .lang(rdfLang)
                    .parse(defaultModel);
            
            // Get size before committing (requires active transaction)
            long tripleCount = defaultModel.size();
            
            dataset.commit();
            
            log.info("Bulk load completed for project: {} - loaded {} triples", projectId, tripleCount);
            
        } catch (Exception e) {
            // Transaction is automatically aborted on commit failure
            // Only abort if still in transaction (parsing error before commit)
            try {
                if (dataset.isInTransaction()) {
                    dataset.abort();
                }
            } catch (Exception abortEx) {
                log.warn("Failed to abort transaction (may already be aborted): {}", abortEx.getMessage());
            }
            log.error("Bulk load failed for project: {}", projectId, e);
            throw new RuntimeException("Bulk load failed", e);
        } finally {
            try {
                if (dataset.isInTransaction()) {
                    dataset.end();
                }
            } catch (Exception endEx) {
                log.warn("Failed to end transaction: {}", endEx.getMessage());
            }
        }
    }
    
    /**
     * Clear all data for a project
     */
    public void clearDataset(String projectId) {
        Dataset dataset = getDataset(projectId);
        
        log.info("Clearing dataset for project: {}", projectId);
        
        try {
            dataset.begin(ReadWrite.WRITE);
            
            Model defaultModel = dataset.getDefaultModel();
            defaultModel.removeAll();
            
            dataset.commit();
            log.info("Dataset cleared for project: {}", projectId);
            
        } catch (Exception e) {
            dataset.abort();
            log.error("Failed to clear dataset for project: {}", projectId, e);
            throw new RuntimeException("Failed to clear dataset", e);
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Get prefix mappings from the dataset
     */
    public Map<String, String> getPrefixes(String projectId) {
        Dataset dataset = getDataset(projectId);
        Map<String, String> prefixes = new HashMap<>();
        
        try {
            dataset.begin(ReadWrite.READ);
            
            Model defaultModel = dataset.getDefaultModel();
            prefixes.putAll(defaultModel.getNsPrefixMap());
            
            dataset.commit();
            
        } catch (Exception e) {
            dataset.abort();
            log.error("Failed to get prefixes for project: {}", projectId, e);
        } finally {
            dataset.end();
        }
        
        return prefixes;
    }
    
    /**
     * Set prefix mappings in the dataset
     */
    public void setPrefixes(String projectId, Map<String, String> prefixes) {
        Dataset dataset = getDataset(projectId);
        
        try {
            dataset.begin(ReadWrite.WRITE);
            
            Model defaultModel = dataset.getDefaultModel();
            prefixes.forEach(defaultModel::setNsPrefix);
            
            dataset.commit();
            log.debug("Set {} prefixes for project: {}", prefixes.size(), projectId);
            
        } catch (Exception e) {
            dataset.abort();
            log.error("Failed to set prefixes for project: {}", projectId, e);
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Count triples in dataset
     */
    public long countTriples(String projectId) {
        Dataset dataset = getDataset(projectId);
        
        try {
            dataset.begin(ReadWrite.READ);
            
            Model defaultModel = dataset.getDefaultModel();
            long count = defaultModel.size();
            
            dataset.commit();
            return count;
            
        } catch (Exception e) {
            dataset.abort();
            log.error("Failed to count triples for project: {}", projectId, e);
            return 0;
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Export dataset to RDF/XML
     */
    public void exportToFile(String projectId, Path outputPath, Lang outputFormat) {
        Dataset dataset = getDataset(projectId);
        
        log.info("Exporting dataset for project: {} to {}", projectId, outputPath);
        
        try {
            dataset.begin(ReadWrite.READ);
            
            Model defaultModel = dataset.getDefaultModel();
            
            // Write to file
            try (var out = Files.newOutputStream(outputPath)) {
                RDFDataMgr.write(out, defaultModel, outputFormat);
            }
            
            dataset.commit();
            log.info("Export completed for project: {}", projectId);
            
        } catch (Exception e) {
            dataset.abort();
            log.error("Export failed for project: {}", projectId, e);
            throw new RuntimeException("Export failed", e);
        } finally {
            dataset.end();
        }
    }
    
    /**
     * Check if dataset exists for project
     */
    public boolean datasetExists(String projectId) {
        Path tdb2Path = getTdb2Path(projectId);
        return Files.exists(tdb2Path) && Files.isDirectory(tdb2Path);
    }
    
    /**
     * Close a specific dataset
     */
    public void closeDataset(String projectId) {
        Dataset dataset = datasetCache.remove(projectId);
        if (dataset != null) {
            try {
                dataset.close();
                log.info("Closed dataset for project: {}", projectId);
            } catch (Exception e) {
                log.error("Error closing dataset for project: {}", projectId, e);
            }
        }
    }
    
    /**
     * Get dataset statistics
     */
    public Map<String, Object> getStatistics(String projectId) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            long tripleCount = countTriples(projectId);
            stats.put("tripleCount", tripleCount);
            stats.put("projectId", projectId);
            stats.put("datasetExists", datasetExists(projectId));
            stats.put("tdb2Path", getTdb2Path(projectId).toString());
            
            // Get prefix count
            Map<String, String> prefixes = getPrefixes(projectId);
            stats.put("prefixCount", prefixes.size());
            
        } catch (Exception e) {
            log.error("Failed to get statistics for project: {}", projectId, e);
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
    
    /**
     * Cleanup: close all datasets on application shutdown
     */
    @PreDestroy
    public void cleanup() {
        log.info("Closing all TDB2 datasets ({} open)", datasetCache.size());
        
        datasetCache.forEach((projectId, dataset) -> {
            try {
                dataset.close();
                log.info("Closed dataset for project: {}", projectId);
            } catch (Exception e) {
                log.error("Error closing dataset for project: {}", projectId, e);
            }
        });
        
        datasetCache.clear();
    }
}