package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.util.Optional;
import java.util.OptionalLong;

@Slf4j
@Component
public class AssistantInsertionSnapper {

    private final StorageManager storageManager;

    public AssistantInsertionSnapper(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public boolean supports(String format) {
        return format != null && CodeViewSubjectIndex.supports(format);
    }

    public OptionalLong nextStatementBoundary(String projectId, String format, long insertLine,
                                              StorageManager.ContentScope scope) {
        if (!supports(format) || insertLine <= 0) {
            return OptionalLong.empty();
        }
        try {
            Optional<SubjectRangeIndex> index = CodeViewSubjectIndex.forFile(
                    storageManager.resolveCodeViewFile(projectId, format, scope), format);
            return index.map(i -> boundaryAfter(i, insertLine)).orElse(OptionalLong.empty());
        } catch (Exception e) {
            log.warn("[Assistant] Insertion boundary lookup failed for {}:{}: {}", format, insertLine, e.getMessage());
            return OptionalLong.empty();
        }
    }

    static OptionalLong boundaryAfter(SubjectRangeIndex index, long insertLine) {
        return index.blocks().stream()
                .filter(block -> block.startLine() < insertLine && block.endLine() >= insertLine)
                .mapToLong(block -> block.endLine() + 1)
                .max();
    }
}
