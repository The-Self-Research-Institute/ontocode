package self.research.ontology.swrl.dto;

public class RuleStatistics {
    private long totalRules;
    private long enabledRules;
    private long disabledRules;
    private long totalExecutions;
    private long averageExecutionTime;
    private String mostUsedCategory;

    // Getters and setters
    public long getTotalRules() { return totalRules; }
    public void setTotalRules(long totalRules) { this.totalRules = totalRules; }
    
    public long getEnabledRules() { return enabledRules; }
    public void setEnabledRules(long enabledRules) { this.enabledRules = enabledRules; }
    
    public long getDisabledRules() { return disabledRules; }
    public void setDisabledRules(long disabledRules) { this.disabledRules = disabledRules; }
    
    public long getTotalExecutions() { return totalExecutions; }
    public void setTotalExecutions(long totalExecutions) { this.totalExecutions = totalExecutions; }
    
    public long getAverageExecutionTime() { return averageExecutionTime; }
    public void setAverageExecutionTime(long averageExecutionTime) { 
        this.averageExecutionTime = averageExecutionTime; 
    }
    
    public String getMostUsedCategory() { return mostUsedCategory; }
    public void setMostUsedCategory(String mostUsedCategory) { 
        this.mostUsedCategory = mostUsedCategory; 
    }
}