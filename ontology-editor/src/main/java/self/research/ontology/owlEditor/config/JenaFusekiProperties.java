package self.research.ontology.owlEditor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ontocode.fuseki")
public class JenaFusekiProperties {

    private String queryEndpoint = "http://localhost:3030/ontocode/query";
    private String updateEndpoint = "http://localhost:3030/ontocode/update";
    private String gspEndpoint = "http://localhost:3030/ontocode/data";
    private int queryTimeoutSeconds = 300;
    private int connectionPoolSize = 10;

    public String getQueryEndpoint() {
        return queryEndpoint;
    }

    public void setQueryEndpoint(String queryEndpoint) {
        this.queryEndpoint = queryEndpoint;
    }

    public String getUpdateEndpoint() {
        return updateEndpoint;
    }

    public void setUpdateEndpoint(String updateEndpoint) {
        this.updateEndpoint = updateEndpoint;
    }

    public String getGspEndpoint() {
        return gspEndpoint;
    }

    public void setGspEndpoint(String gspEndpoint) {
        this.gspEndpoint = gspEndpoint;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getConnectionPoolSize() {
        return connectionPoolSize;
    }

    public void setConnectionPoolSize(int connectionPoolSize) {
        this.connectionPoolSize = connectionPoolSize;
    }
}
