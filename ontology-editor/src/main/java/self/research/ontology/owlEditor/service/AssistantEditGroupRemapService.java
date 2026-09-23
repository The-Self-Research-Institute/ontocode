package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssistantEditGroupRemapService {

    public List<AssistantEditGroupDocument> remap(AssistantEditGroupDocument committed,
                                                    List<AssistantEditGroupDocument> siblings) {
        List<AssistantEditGroupDocument> touched = new ArrayList<>();
        Instant now = Instant.now();

        for (AssistantEditGroupDocument sibling : siblings) {
            if (sibling.getStatus() != AssistantEditGroupStatus.PENDING) {
                continue;
            }

            boolean overlaps = sibling.getEdits().stream()
                    .anyMatch(siblingEdit -> committed.getEdits().stream()
                            .anyMatch(committedEdit -> overlaps(siblingEdit, committedEdit)));
            if (overlaps) {
                sibling.setStatus(AssistantEditGroupStatus.STALE);
                sibling.setStaleReason("overlaps committed group " + committed.getId());
                sibling.setUpdatedAt(now);
                touched.add(sibling);
                continue;
            }

            boolean anyShifted = false;
            for (EditEntry siblingEdit : sibling.getEdits()) {
                long shift = committed.getEdits().stream()
                        .filter(committedEdit -> isBefore(committedEdit, siblingEdit))
                        .mapToLong(EditEntry::getLineDelta)
                        .sum();
                if (shift != 0) {
                    siblingEdit.setStartLine(siblingEdit.getStartLine() + shift);
                    anyShifted = true;
                }
            }
            if (anyShifted) {
                sibling.setUpdatedAt(now);
                touched.add(sibling);
            }
        }

        return touched;
    }

    private boolean overlaps(EditEntry a, EditEntry b) {
        long aEnd = a.getStartLine() + a.getLineCount();
        long bEnd = b.getStartLine() + b.getLineCount();
        return a.getStartLine() < bEnd && b.getStartLine() < aEnd;
    }

    private boolean isBefore(EditEntry committedEdit, EditEntry siblingEdit) {
        long committedEnd = committedEdit.getStartLine() + committedEdit.getLineCount();
        return committedEnd <= siblingEdit.getStartLine();
    }
}
