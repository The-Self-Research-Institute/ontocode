package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.AssistantAuditEntryDocument;

public interface AssistantAuditRepository {

    AssistantAuditEntryDocument save(AssistantAuditEntryDocument entry);
}
