package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;

import java.util.List;

final class SimpleTupleQueryResult implements TupleQueryResult {
    private final List<String> bindingNames;
    private final List<BindingSet> bindings;
    private int currentIndex = -1;

    SimpleTupleQueryResult(List<String> bindingNames, List<BindingSet> bindings) {
        this.bindingNames = bindingNames;
        this.bindings = bindings;
    }

    @Override
    public List<String> getBindingNames() {
        return bindingNames;
    }

    @Override
    public void close() {
    }

    @Override
    public boolean hasNext() {
        return currentIndex < bindings.size() - 1;
    }

    @Override
    public BindingSet next() {
        currentIndex++;
        return bindings.get(currentIndex);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
