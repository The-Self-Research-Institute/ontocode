package self.research.ontology.owlEditor.dto;

public record AssistantUsageReport(String provider, String model, Long latencyMs, Long inputTokens,
                                   Long outputTokens, Long cacheReadTokens, Long cacheWriteTokens) {
}
