package self.research.ontology.owlEditor.service;

import java.io.IOException;
import java.util.List;

class PageBackedRangeMatcher extends CodeViewRangeMatcher {

    private final StorageManager storageManager;

    PageBackedRangeMatcher(StorageManager storageManager) {
        super(storageManager);
        this.storageManager = storageManager;
    }

    @Override
    public boolean allMatch(String projectId, String format, List<ExpectedRange> ranges) {
        try {
            for (ExpectedRange range : ranges) {
                if (range.lineCount() == 0) {
                    continue;
                }
                StorageManager.CodeViewPage page = storageManager.readCodeViewPage(
                        projectId, format, range.startLine(), range.lineCount());
                if (page == null || !page.content().equals(range.originalText())) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean allMatch(String projectId, String format, List<ExpectedRange> ranges,
                            StorageManager.ContentScope scope) {
        return allMatch(projectId, format, ranges);
    }
}
