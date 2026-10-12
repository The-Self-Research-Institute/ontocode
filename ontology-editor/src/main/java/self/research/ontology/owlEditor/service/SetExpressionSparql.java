package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SetExpressionSparql {

    private SetExpressionSparql() {
    }

    static String disjointUnion(String classIri, String[] memberIris) {
        log.info("[MUTATION] buildDisjointUnionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   memberIris: {}", String.join(", ", memberIris));
        for (int i = 0; i < memberIris.length; i++) {
            memberIris[i] = memberIris[i].trim();
        }
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT DATA {\n");
        insertBuilder.append("  <").append(classIri).append("> owl:disjointUnionOf _:list0 .\n");
        appendList(insertBuilder, memberIris);
        insertBuilder.append("}\n");
        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated disjoint union SPARQL:");
        log.info("[MUTATION]   {}", sparql);
        return sparql;
    }

    static String intersection(String classIri, String[] memberIris, String axiomType, String axiomPredicate) {
        return anonymous("intersection", "owl:intersectionOf", classIri, memberIris, axiomType, axiomPredicate);
    }

    static String union(String classIri, String[] memberIris, String axiomType, String axiomPredicate) {
        return anonymous("union", "owl:unionOf", classIri, memberIris, axiomType, axiomPredicate);
    }

    static String oneOf(String classIri, String[] individualIris, String axiomType, String axiomPredicate) {
        return anonymous("oneOf", "owl:oneOf", classIri, individualIris, axiomType, axiomPredicate);
    }

    private static String anonymous(String node, String listPredicate, String classIri, String[] memberIris,
                                    String axiomType, String axiomPredicate) {
        String name = Character.toUpperCase(node.charAt(0)) + node.substring(1);
        log.info("[MUTATION] build{}Sparql called:", name);
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   {}: {}", "oneOf".equals(node) ? "individualIris" : "memberIris", String.join(", ", memberIris));
        log.info("[MUTATION]   axiomType: {}", axiomType);
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT DATA {\n");
        insertBuilder.append("  <").append(classIri).append("> ").append(axiomPredicate).append(" _:").append(node).append(" .\n");
        insertBuilder.append("  _:").append(node).append(' ').append(listPredicate).append(" _:list0 .\n");
        appendList(insertBuilder, memberIris);
        insertBuilder.append("}\n");
        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated {} SPARQL: {}", node, sparql);
        return sparql;
    }

    private static void appendList(StringBuilder out, String[] members) {
        for (int i = 0; i < members.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == members.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            out.append("  ").append(currentList)
                .append(" rdf:first <").append(members[i].trim()).append("> ;\n");
            out.append("             rdf:rest ").append(nextList).append(" .\n");
        }
    }
}
