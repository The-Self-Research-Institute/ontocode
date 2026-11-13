package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.Values;

import java.util.List;

public final class ProjectKinds {

    private ProjectKinds() {
    }

    public static boolean isFileDocument(Document d) {
        String blob = Values.text(d, "gridfsFileId");
        boolean hasBlob = blob != null && !blob.isBlank();
        return hasBlob || !(d.get("members") instanceof List<?>);
    }

    public static boolean isContainer(Document d) {
        return !isFileDocument(d);
    }
}
