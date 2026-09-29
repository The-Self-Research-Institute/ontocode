package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.service.AssistantRenameService.DerivedEdit;

import java.util.ArrayList;
import java.util.List;

final class RenameScan {

    final List<DerivedEdit> edits = new ArrayList<>();
    int occurrences;
    String problem;

    boolean addEdit(long lineNo, String original, String rewritten, int lineOccurrences, int maxLines) {
        if (edits.size() >= maxLines) {
            problem = "This rename touches more than " + maxLines + " lines, which is over the limit for one "
                    + "derived rename group, so no rename was generated.";
            return false;
        }
        edits.add(new DerivedEdit(lineNo, original, rewritten));
        occurrences += lineOccurrences;
        return true;
    }
}
