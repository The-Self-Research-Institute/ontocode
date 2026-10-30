package self.research.ontology.swrl.service;

import org.swrlapi.builtins.AbstractSWRLBuiltInLibrary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class BuiltInCatalog {

    private static final Map<String, Set<String>> REGISTERED = load();

    private BuiltInCatalog() {
    }

    private static Map<String, Set<String>> load() {
        Map<String, Set<String>> libraries = new LinkedHashMap<>();
        for (AbstractSWRLBuiltInLibrary library : new AbstractSWRLBuiltInLibrary[]{
                new org.swrlapi.builtins.swrlb.SWRLBuiltInLibraryImpl(),
                new org.swrlapi.builtins.swrlm.SWRLBuiltInLibraryImpl(),
                new org.swrlapi.builtins.swrlx.SWRLBuiltInLibraryImpl(),
                new org.swrlapi.builtins.temporal.SWRLBuiltInLibraryImpl(),
                new org.swrlapi.builtins.sqwrl.SWRLBuiltInLibraryImpl(),
                new org.swrlapi.builtins.tbox.SWRLBuiltInLibraryImpl(),
                new org.swrlapi.builtins.abox.SWRLBuiltInLibraryImpl(),
                new org.swrlapi.builtins.rbox.SWRLBuiltInLibraryImpl()}) {
            libraries.put(library.getPrefix(), Collections.unmodifiableSet(new TreeSet<>(library.getBuiltInNames())));
        }
        return Collections.unmodifiableMap(libraries);
    }

    static Map<String, Set<String>> registered() {
        return REGISTERED;
    }

    static boolean isLibraryPrefix(String prefix) {
        return REGISTERED.containsKey(prefix);
    }

    static Set<String> namesIn(String prefix) {
        return REGISTERED.getOrDefault(prefix, Set.of());
    }
}
