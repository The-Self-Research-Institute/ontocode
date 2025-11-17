package self.research.ontology.owlEditor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ontocode.tdb2")
public class Tdb2Properties {

    private String dataDir = "./data";
    private boolean enableStats = true;
    private long progressInterval = 100000;
    private boolean autoCompact = false;
    private int queryTimeoutSeconds = 300;
    private boolean enableQueryCache = false;
    private int cacheSize = 100;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public boolean isEnableStats() {
        return enableStats;
    }

    public void setEnableStats(boolean enableStats) {
        this.enableStats = enableStats;
    }

    public long getProgressInterval() {
        return progressInterval;
    }

    public void setProgressInterval(long progressInterval) {
        this.progressInterval = progressInterval;
    }

    public boolean isAutoCompact() {
        return autoCompact;
    }

    public void setAutoCompact(boolean autoCompact) {
        this.autoCompact = autoCompact;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public boolean isEnableQueryCache() {
        return enableQueryCache;
    }

    public void setEnableQueryCache(boolean enableQueryCache) {
        this.enableQueryCache = enableQueryCache;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }
}

