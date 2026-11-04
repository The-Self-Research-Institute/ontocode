package self.research.ontology.swrl.builtin;

import org.semanticweb.owlapi.model.OWLOntology;
import org.swrlapi.builtins.AbstractSWRLBuiltInLibrary;
import org.swrlapi.builtins.SWRLBuiltInLibrary;
import org.swrlapi.builtins.SWRLBuiltInLibraryManager;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.factory.SWRLAPIFactory;
import org.swrlapi.sqwrl.SQWRLQueryEngine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuiltInLibraries {

    private static final Field LIBRARIES = field("swrlBuiltInLibraryImplementations");
    private static final Method REGISTER = register();

    private BuiltInLibraries() {
    }

    public static List<AbstractSWRLBuiltInLibrary> libraries() {
        List<AbstractSWRLBuiltInLibrary> libraries = new ArrayList<>();
        libraries.add(new CorrectedSwrlb());
        libraries.add(new CorrectedSwrlm());
        libraries.add(new org.swrlapi.builtins.swrlx.SWRLBuiltInLibraryImpl());
        libraries.add(new CorrectedTemporal());
        libraries.add(new CorrectedSqwrl());
        libraries.add(new org.swrlapi.builtins.tbox.SWRLBuiltInLibraryImpl());
        libraries.add(new org.swrlapi.builtins.abox.SWRLBuiltInLibraryImpl());
        libraries.add(new org.swrlapi.builtins.rbox.SWRLBuiltInLibraryImpl());
        return libraries;
    }

    public static SWRLRuleEngine createRuleEngine(OWLOntology ontology) {
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        install(engine.getSWRLAPIOWLOntology().getSWRLBuiltInLibraryManager());
        return engine;
    }

    public static SQWRLQueryEngine createQueryEngine(OWLOntology ontology) {
        SQWRLQueryEngine engine = SWRLAPIFactory.createSQWRLQueryEngine(ontology);
        install(engine.getSWRLAPIOWLOntology().getSWRLBuiltInLibraryManager());
        return engine;
    }

    @SuppressWarnings("unchecked")
    static void install(SWRLBuiltInLibraryManager manager) {
        try {
            Map<String, SWRLBuiltInLibrary> installed = (Map<String, SWRLBuiltInLibrary>) LIBRARIES.get(manager);
            for (AbstractSWRLBuiltInLibrary library : List.of(new CorrectedSwrlb(), new CorrectedSwrlm(), new CorrectedTemporal(), new CorrectedSqwrl())) {
                installed.put(library.getPrefix(), library);
                REGISTER.invoke(manager, library.getPrefix(), library.getNamespace(), library.getBuiltInNames());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The corrected function libraries could not be installed", e);
        }
    }

    private static Field field(String name) {
        try {
            Field field = SWRLBuiltInLibraryManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Method register() {
        try {
            Method method = SWRLBuiltInLibraryManager.class.getDeclaredMethod("registerSWRLBuiltIns", String.class, String.class, Set.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
