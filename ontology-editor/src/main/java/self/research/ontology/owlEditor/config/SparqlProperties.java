package self.research.ontology.owlEditor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;

@Configuration
@ConfigurationProperties(prefix = "sparql")
@Validated
public class SparqlProperties {
    @NotBlank 
    private String endpointUrl;
    private String updateEndpointUrl;
    private String projectGraphTemplate = "urn:ontocode:project:%s";
    private int connectionTimeoutMs = 30000;
    private int readTimeoutMs = 60000;
    private int maxConnections = 50;
    private String username;
    private String password;

    public String getEndpointUrl() { 
        return endpointUrl; 
    }
    
    public void setEndpointUrl(String endpointUrl) { 
        this.endpointUrl = endpointUrl; 
    }

    public String getUpdateEndpointUrl() {
        return updateEndpointUrl != null ? updateEndpointUrl : endpointUrl + "/statements";
    }
    
    public void setUpdateEndpointUrl(String updateEndpointUrl) { 
        this.updateEndpointUrl = updateEndpointUrl; 
    }

    public String getProjectGraphTemplate() { 
        return projectGraphTemplate; 
    }
    
    public void setProjectGraphTemplate(String projectGraphTemplate) { 
        this.projectGraphTemplate = projectGraphTemplate; 
    }
    
    public String getProjectGraphUri(String projectId) { 
        return String.format(projectGraphTemplate, projectId); 
    }

    public int getConnectionTimeoutMs() { 
        return connectionTimeoutMs; 
    }
    
    public void setConnectionTimeoutMs(int connectionTimeoutMs) { 
        this.connectionTimeoutMs = connectionTimeoutMs; 
    }
    
    public int getReadTimeoutMs() { 
        return readTimeoutMs; 
    }
    
    public void setReadTimeoutMs(int readTimeoutMs) { 
        this.readTimeoutMs = readTimeoutMs; 
    }
    
    public int getMaxConnections() { 
        return maxConnections; 
    }
    
    public void setMaxConnections(int maxConnections) { 
        this.maxConnections = maxConnections; 
    }
    
    public String getUsername() { 
        return username; 
    }
    
    public void setUsername(String username) { 
        this.username = username; 
    }
    
    public String getPassword() { 
        return password; 
    }
    
    public void setPassword(String password) { 
        this.password = password; 
    }
}