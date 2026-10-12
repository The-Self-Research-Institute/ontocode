package self.research.ontology.owlEditor.dto;

import self.research.ontology.owlEditor.service.AssistantContextToolService.Target;

import java.util.List;

public record ReadContextRequest(List<Target> targets, String kind) {}
