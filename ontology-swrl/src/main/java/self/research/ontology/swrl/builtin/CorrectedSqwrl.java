package self.research.ontology.swrl.builtin;

import java.util.LinkedHashSet;
import java.util.Set;

public class CorrectedSqwrl extends org.swrlapi.builtins.sqwrl.SWRLBuiltInLibraryImpl {

    private final Set<String> names = new LinkedHashSet<>();

    public CorrectedSqwrl() {
        names.addAll(super.getBuiltInNames());
        names.add("countDistinct");
    }

    @Override
    public Set<String> getBuiltInNames() {
        return names;
    }
}
