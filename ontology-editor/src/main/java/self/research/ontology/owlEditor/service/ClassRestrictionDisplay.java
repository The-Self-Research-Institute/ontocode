package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.rio.helpers.NTriplesUtil;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.vocab.OWLFacet;

import java.util.List;
import java.util.function.Function;

final class ClassRestrictionDisplay {
    private ClassRestrictionDisplay() {}

    static String format(List<BindingSet> rows, Function<String, String> formatIri) {
        BindingSet first = rows.getFirst();
        String property = first.hasBinding("propLabel") ? value(first, "propLabel") : formatIri.apply(value(first, "prop"));
        String type = first.hasBinding("restrictionType") ? value(first, "restrictionType") : "some";
        String filler = first.hasBinding("fillerLabel") ? value(first, "fillerLabel") : formatIri.apply(value(first, "filler"));
        String datatype = value(first, "datatype");
        if (!datatype.isEmpty()) {
            List<String> facets = rows.stream().map(ClassRestrictionDisplay::formatFacet)
                    .filter(facet -> !facet.isEmpty()).distinct().sorted().toList();
            filler = formatIri.apply(datatype) + (facets.isEmpty() ? "" : "[" + String.join(", ", facets) + "]");
        }
        String cardinality = value(first, "card");
        if ("Self".equals(filler)) return property + " some Self";
        if (cardinality.isEmpty()) return property + " " + type + " " + filler;
        return property + " " + type + " " + cardinality + (first.hasBinding("unqualified") ? "" : " " + filler);
    }

    private static String formatFacet(BindingSet row) {
        String predicate = value(row, "facet");
        if (predicate.isEmpty() || !(row.getValue("bound") instanceof Literal bound)) return "";
        OWLFacet facet = OWLFacet.getFacet(IRI.create(predicate));
        if (facet == null) return "";
        String lexical = XMLDatatypeUtil.isNumericDatatype(bound.getDatatype())
                ? bound.getLabel() : NTriplesUtil.toNTriplesString(bound);
        return facet.getSymbolicForm() + " " + lexical;
    }

    private static String value(BindingSet row, String name) {
        return row.hasBinding(name) ? row.getValue(name).stringValue() : "";
    }
}
