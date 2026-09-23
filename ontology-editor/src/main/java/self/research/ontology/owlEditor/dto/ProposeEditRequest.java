package self.research.ontology.owlEditor.dto;

import java.util.List;

public record ProposeEditRequest(List<EditGroupInput> groups) {

    public record EditRange(long startLine, int lineCount) {}

    public record EditInput(String targetPath, EditRange range, String originalText, String newText) {}

    public record EditGroupInput(String clientGroupId, List<EditInput> edits) {}
}
