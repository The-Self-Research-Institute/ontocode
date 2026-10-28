package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlJenaGuardTest {

    static final String A = SparqlGraphUris.mainProjectGraph("projA");
    static final String A_PART = A + "/ns/x";
    static final String DRAFT_A = SparqlGraphUris.userDraftGraph("projA", "userA");
    static final String P = "http://e/p";
    static final Set<String> PUBLIC = new LinkedHashSet<>(List.of(A, A_PART));
    static final Set<String> DRAFT = new LinkedHashSet<>(List.of(DRAFT_A));

    static List<String> hostile() {
        return SparqlGuardCases.hostile();
    }

    @ParameterizedTest
    @MethodSource("hostile")
    void jenaGuardAloneRejectsTheWholeIsolationMatrix(String query) {
        String text = SparqlQueryGuard.decodeUnicodeEscapes(query);
        assertThrows(SparqlQueryRejectedException.class, () -> JenaQueryGuard.check(text, PUBLIC), query);
        assertThrows(SparqlQueryRejectedException.class, () -> JenaQueryGuard.check(text, DRAFT), query);
    }

    @ParameterizedTest
    @MethodSource("hostile")
    void combinedGuardRejectsTheWholeIsolationMatrix(String query) {
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlQueryGuard.check(query, PUBLIC), query);
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlQueryGuard.check(query, DRAFT), query);
    }

    @ParameterizedTest
    @MethodSource("hostile")
    void rdf4jGuardAloneStillRejectsTheWholeIsolationMatrix(String query) {
        String text = SparqlQueryGuard.decodeUnicodeEscapes(query);
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlQueryGuard.checkRdf4j(text, PUBLIC), query);
    }

    @Test
    void jenaMessagesMatchTheRdf4jMessagesForTheSameRule() {
        String[][] cases = {
                {"SELECT * FROM <http://x/g> WHERE { ?s ?p ?o }", "FROM and FROM NAMED are not supported"},
                {"INSERT DATA { <http://x/a> <http://x/p> 1 }", "This is an UPDATE"},
                {"SELECT * WHERE { SERVICE <http://x/s> { ?s ?p ?o } }", "SERVICE is not supported"},
                {"SELECT * WHERE { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } }", "GRAPH <http://ontocode.org/project/projB> is not available"},
                {"SELECT (<java:x.Y>(1) AS ?x) WHERE { ?s ?p ?o }", "The function <java:x.Y> is not supported"},
                {"SELECT * WHERE { ?s <java:x.Y> ?o }", "Extension property functions are not supported"},
        };
        for (String[] c : cases) {
            SparqlQueryRejectedException j = assertThrows(SparqlQueryRejectedException.class,
                    () -> JenaQueryGuard.check(c[0], PUBLIC), c[0]);
            SparqlQueryRejectedException r = assertThrows(SparqlQueryRejectedException.class,
                    () -> SparqlQueryGuard.checkRdf4j(c[0], PUBLIC), c[0]);
            assertTrue(j.getMessage().startsWith(c[1]), j.getMessage());
            assertEquals(r.getMessage(), j.getMessage());
        }
    }

    @Test
    void formsComeFromJena() {
        assertEquals(SparqlQueryGuard.Kind.SELECT, JenaQueryGuard.check("# ASK\nSELECT * { ?s ?p ?o }", PUBLIC));
        assertEquals(SparqlQueryGuard.Kind.ASK, JenaQueryGuard.check("PREFIX select: <http://x/> ASK { ?s ?p ?o }", PUBLIC));
        assertEquals(SparqlQueryGuard.Kind.CONSTRUCT, JenaQueryGuard.check("CONSTRUCT WHERE { ?s ?p ?o }", PUBLIC));
        assertEquals(SparqlQueryGuard.Kind.CONSTRUCT, JenaQueryGuard.check("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", PUBLIC));
        assertEquals(SparqlQueryGuard.Kind.DESCRIBE, JenaQueryGuard.check("DESCRIBE <http://x/a>", PUBLIC));
        assertEquals(SparqlQueryGuard.Kind.DESCRIBE, JenaQueryGuard.check("DESCRIBE ?s WHERE { ?s ?p ?o }", PUBLIC));
    }

    @Test
    void allowedQueriesPassBothParsersAndAgreeOnTheForm() {
        String[] ok = {
                "SELECT * WHERE { GRAPH <" + A + "> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A_PART + "> { ?s <" + P + ">+ ?o } }",
                "SELECT * WHERE { GRAPH ?g { ?s ?p ?o } }",
                "# FROM <http://ontocode.org/project/projB>\nPREFIX from: <http://e/from#> PREFIX service: <http://e/service#>\n"
                        + "SELECT ?o WHERE { ?s <" + P + "> ?o . FILTER(?o != \"FROM <x> SERVICE <y> GRAPH\") }",
                "SELECT ?s WHERE { VALUES (?a ?b) { (1 UNDEF) (UNDEF 2) } ?s ?p ?a }",
                "SELECT * WHERE { ?s ?p ?o FILTER NOT EXISTS { ?s <" + P + "> ?x } }",
                "SELECT * WHERE { ?s ?p ?o MINUS { ?s <" + P + "> ?x } }",
                "SELECT * WHERE { ?s (<" + P + ">|^<" + P + ">)/<" + P + ">* ?o }",
                "SELECT * WHERE { ?s !(<" + P + ">|^<" + P + ">) ?o }",
                "SELECT ?s (COUNT(?o) AS ?c) WHERE { ?s ?p ?o } GROUP BY ?s HAVING (COUNT(?o) > 1) ORDER BY DESC(?c) LIMIT 5 OFFSET 2",
                "SELECT * WHERE { { SELECT ?s WHERE { ?s ?p ?o } LIMIT 3 } ?s ?p2 ?o2 }",
                "ASK { ?s ?p ?o }",
                "CONSTRUCT { ?s <" + P + "> ?o } WHERE { ?s ?p ?o }",
                "DESCRIBE ?s WHERE { ?s ?p ?o }",
        };
        for (String q : ok) {
            SparqlQueryGuard.Checked c = SparqlQueryGuard.check(q, PUBLIC);
            assertEquals(JenaQueryGuard.check(c.text(), PUBLIC), c.kind(), q);
        }
    }

    static List<String> builtIns() {
        String[] calls = {"STR(?o)", "LANG(?o)", "DATATYPE(?o)", "BOUND(?o)", "IRI(\"http://x/y\")", "URI(\"http://x/y\")",
                "BNODE()", "BNODE(\"a\")", "RAND()", "NOW()", "UUID()", "STRUUID()", "STRLEN(?o)", "SUBSTR(?o, 1, 2)",
                "UCASE(?o)", "LCASE(?o)", "STRSTARTS(?o, \"A\")", "STRENDS(?o, \"A\")", "CONTAINS(?o, \"A\")",
                "STRBEFORE(?o, \"-\")", "STRAFTER(?o, \"-\")", "ENCODE_FOR_URI(?o)", "CONCAT(?o, \"x\")",
                "LANGMATCHES(\"en\", \"*\")", "REGEX(?o, \"A\", \"i\")", "REPLACE(?o, \"A\", \"B\")", "ABS(1)", "ROUND(1.5)",
                "CEIL(1.5)", "FLOOR(1.5)", "YEAR(NOW())", "MONTH(NOW())", "DAY(NOW())", "HOURS(NOW())", "MINUTES(NOW())",
                "SECONDS(NOW())", "TIMEZONE(NOW())", "TZ(NOW())", "MD5(?o)", "SHA1(?o)", "SHA256(?o)", "SHA384(?o)",
                "SHA512(?o)", "COALESCE(?zz, 1)", "IF(true, 1, 2)", "STRLANG(?o, \"en\")",
                "STRDT(?o, <http://www.w3.org/2001/XMLSchema#string>)", "sameTerm(?o, ?o)", "isIRI(?o)", "isURI(?o)",
                "isBLANK(?o)", "isLITERAL(?o)", "isNUMERIC(?o)", "?o IN (1, 2)", "?o NOT IN (1, 2)", "?o = 1 && ?o != 2 || !BOUND(?o)",
                "-?o + 1 * 2 / 3", "<http://www.w3.org/2001/XMLSchema#integer>(\"3\")",
                "<http://www.w3.org/2005/xpath-functions#upper-case>(?o)", "<http://www.w3.org/2005/xpath-functions/math#sqrt>(4)",
                "EXISTS { ?s ?p ?o }", "NOT EXISTS { ?s ?p ?o }"};
        List<String> out = new ArrayList<>();
        for (String c : calls) {
            out.add("SELECT (" + c + " AS ?x) WHERE { ?s <" + P + "> ?o }");
        }
        out.add("SELECT (COUNT(*) AS ?c) (COUNT(DISTINCT ?o) AS ?d) (SAMPLE(?o) AS ?s1) (GROUP_CONCAT(?o; separator=\",\") AS ?g) "
                + "(MIN(?o) AS ?mi) (MAX(?o) AS ?ma) (AVG(1) AS ?av) (SUM(1) AS ?su) WHERE { ?s <" + P + "> ?o }");
        return out;
    }

    @ParameterizedTest
    @MethodSource("builtIns")
    void standardBuiltInsAreAcceptedByBothParsers(String q) {
        SparqlQueryGuard.Checked c = SparqlQueryGuard.checkRdf4j(q, PUBLIC);
        assertEquals(SparqlQueryGuard.Kind.SELECT, JenaQueryGuard.check(q, PUBLIC), q);
        assertEquals(SparqlQueryGuard.Kind.SELECT, c.kind(), q);
        assertEquals(SparqlQueryGuard.Kind.SELECT, SparqlQueryGuard.check(q, PUBLIC).kind(), q);
    }

    @Test
    void builtInListHasAboutSixtyEntries() {
        assertTrue(builtIns().size() >= 60);
    }

    static List<String> disagreementCases() {
        String ex = "http://e/";
        return List.of(
                "SELECT * WHERE { << ?s ?p ?o >> ?q ?r }",
                "SELECT * WHERE { ?s ?p << ?a ?b ?c >> }",
                "SELECT * WHERE { ?s ?p ?o {| <" + P + "> 1 |} }",
                "SELECT * WHERE { ?s ?p ?o ~ <" + ex + "r> }",
                "SELECT (TRIPLE(?s, ?p, ?o) AS ?t) WHERE { ?s ?p ?o }",
                "SELECT (SUBJECT(?t) AS ?x) WHERE { ?s ?p ?t }",
                "SELECT * WHERE { ?s ?p ?o FILTER(isTRIPLE(?o)) }",
                "VERSION \"1.2\" SELECT * WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o LATERAL { ?o ?p2 ?o2 } }",
                "SELECT * WHERE { ?s ?p ?o . LATERAL { SELECT ?o2 WHERE { ?o ?p2 ?o2 } LIMIT 1 } }",
                "SELECT * WHERE { ?s ?p ?o ADJUST(?o, 1) }",
                "SELECT (ADJUST(NOW(), \"PT1H\"^^<http://www.w3.org/2001/XMLSchema#dayTimeDuration>) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (LANGDIR(?o) AS ?l) WHERE { ?s ?p ?o }",
                "SELECT (STRLANGDIR(?o, \"en\", \"ltr\") AS ?l) WHERE { ?s ?p ?o }",
                "SELECT (hasLANG(?o) AS ?l) WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p \"x\"@en--ltr }",
                "SELECT * WHERE { ?s ?p ?o } LIMIT 1 LIMIT 2",
                "SELECT * WHERE { ?s ?p ?o } OFFSET 1 LIMIT 2",
                "SELECT * WHERE { ?s ?p 1_000 }",
                "SELECT * WHERE { ?s ?p 0x1F }",
                "SELECT * WHERE { ?s ?p 1e }",
                "SELECT * WHERE { ?s ?p .5 }",
                "SELECT * WHERE { ?s ?p 5. }",
                "SELECT * WHERE { ?s ?p +.5E-3 }",
                "SELECT * WHERE { ?s ?p 'a\\qb' }",
                "SELECT * WHERE { ?s ?p \"\"\"multi\nline\"\"\" }",
                "SELECT * WHERE { ?s ?p '''it''s''' }",
                "SELECT * WHERE { ?s ?p \"a\"^^<http://www.w3.org/2001/XMLSchema#integer> }",
                "SELECT * WHERE { ?s ?p \"a\"@en-US-x-private }",
                "SELECT * WHERE { ?s ?p \"a\"@1 }",
                "SELECT * WHERE { ?s ?p true }",
                "SELECT * WHERE { VALUES ?x { UNDEF } ?s ?p ?x }",
                "SELECT * WHERE { VALUES (?x ?y) { (UNDEF UNDEF) (1 2) } ?s ?p ?x }",
                "SELECT * WHERE { VALUES () { () } ?s ?p ?o }",
                "SELECT * WHERE { VALUES ?x { } ?s ?p ?x }",
                "SELECT * WHERE { VALUES (?x ?y) { (1) } }",
                "SELECT * WHERE { ?s ?p ?o } VALUES ?x { 1 2 }",
                "SELECT * WHERE { ?s ?p ?o } VALUES (?x ?y) { (1 UNDEF) }",
                "SELECT * WHERE { ?s <" + P + ">{2} ?o }",
                "SELECT * WHERE { ?s <" + P + ">{1,3} ?o }",
                "SELECT * WHERE { ?s <" + P + ">{,3} ?o }",
                "SELECT * WHERE { ?s ^^<" + P + "> ?o }",
                "SELECT * WHERE { ?s !^<" + P + "> ?o }",
                "SELECT * WHERE { ?s !(<" + P + ">|^<" + P + ">)+ ?o }",
                "SELECT * WHERE { ?s (<" + P + ">/)+ ?o }",
                "SELECT * WHERE { ?s <" + P + ">?/<" + P + ">+ ?o }",
                "SELECT * WHERE { ?s ((<" + P + ">)*)+ ?o }",
                "SELECT * WHERE { ?s a/a* ?o }",
                "SELECT * WHERE { ?s !a ?o }",
                "SELECT * WHERE { ?s ?p ?o BIND(1 AS ?o) }",
                "SELECT * WHERE { BIND(1 AS ?x) ?s ?p ?x }",
                "SELECT * WHERE { ?s ?p ?o BIND(?o AS ?o2) BIND(?o2 AS ?o3) }",
                "SELECT * WHERE { BIND(?later AS ?x) BIND(1 AS ?later) }",
                "SELECT * WHERE { ?s ?p ?o BIND(?x AS ?x) }",
                "SELECT * WHERE { { ?s ?p ?o BIND(1 AS ?o) } }",
                "SELECT ?o (1 AS ?o) WHERE { ?s ?p ?o }",
                "SELECT (1 AS ?x) (2 AS ?x) WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o } GROUP BY ?s",
                "SELECT ?s ?o WHERE { ?s ?p ?o } GROUP BY ?s",
                "SELECT (COUNT(*) AS ?c) ?s WHERE { ?s ?p ?o }",
                "SELECT (SAMPLE(COUNT(?o)) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o FILTER(COUNT(?o) > 1) }",
                "SELECT * WHERE { ?s ?p ?o } ORDER BY COUNT(?o)",
                "SELECT ?s WHERE { ?s ?p ?o } HAVING (?s = 1)",
                "SELECT * WHERE { _:b ?p ?o . _:b ?q ?r }",
                "SELECT * WHERE { { _:b ?p ?o } { _:b ?q ?r } }",
                "SELECT * WHERE { ?s ?p [] }",
                "SELECT * WHERE { [ <" + P + "> 1 ] ?p ?o }",
                "SELECT * WHERE { (1 2 3) ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o ; . }",
                "SELECT * WHERE { ?s ?p ?o , }",
                "SELECT * WHERE { ?s ?p ?o . . }",
                "SELECT * WHERE { . ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o }}",
                "SELECT * { ?s ?p ?o } ;",
                "SELECT * { ?s ?p ?o } ; SELECT * { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o } # trailing",
                "SELECT * WHERE { ?s ?p <http://e/a b> }",
                "SELECT * WHERE { ?s ?p <http://e/a\\u0020b> }",
                "SELECT * WHERE { ?s ?p <> }",
                "SELECT * WHERE { ?s ?p <rel> }",
                "SELECT * WHERE { ?s ?p <http://e/\u00e9> }",
                "SELECT * WHERE { ?s ?p <http://[::1]/> }",
                "SELECT * WHERE { ?s ?p <urn:uuid:1234> }",
                "SELECT * WHERE { ?s ?p <http://e/a{b}> }",
                "SELECT * WHERE { ?s ?p <http://e/a|b> }",
                "SELECT * WHERE { ?s ?p <http://e/a^b> }",
                "PREFIX : <http://e/> SELECT * WHERE { :a:b :c ?o }",
                "PREFIX : <http://e/> SELECT * WHERE { :a.b :c ?o }",
                "PREFIX : <http://e/> SELECT * WHERE { :a. :c ?o }",
                "PREFIX : <http://e/> SELECT * WHERE { :a%20b :c ?o }",
                "PREFIX : <http://e/> SELECT * WHERE { :a\\-b :c ?o }",
                "PREFIX : <http://e/> SELECT * WHERE { :1a :c ?o }",
                "PREFIX : <http://e/> PREFIX : <http://f/> SELECT * WHERE { :a :c ?o }",
                "PREFIX ex: <http://e/> SELECT * WHERE { ex:a ez:c ?o }",
                "SELECT * WHERE { ?s ?p ?o } LIMIT -1",
                "SELECT * WHERE { ?s ?p ?o } LIMIT 1.5",
                "SELECT * WHERE { ?s ?p ?o } LIMIT 99999999999999999999",
                "SELECT REDUCED * WHERE { ?s ?p ?o }",
                "SELECT DISTINCT REDUCED * WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o } ORDER BY",
                "SELECT * WHERE { ?s ?p ?o } ORDER BY ASC ?o",
                "SELECT * WHERE { ?s ?p ?o } ORDER BY (?o)",
                "SELECT * WHERE { ?s ?p ?o } ORDER BY STR(?o) DESC(?s)",
                "SELECT * WHERE { ?s ?p ?o } GROUP BY (STR(?o) AS ?x)",
                "SELECT * WHERE { ?s ?p ?o } GROUP BY STR(?o)",
                "SELECT ?x WHERE { ?s ?p ?o } GROUP BY (STR(?o) AS ?x)",
                "SELECT * WHERE { SELECT * WHERE { ?s ?p ?o } }",
                "SELECT * WHERE { { SELECT * WHERE { ?s ?p ?o } } UNION { SELECT * WHERE { ?s ?p ?o } } }",
                "SELECT * WHERE { OPTIONAL { ?s ?p ?o } }",
                "SELECT * WHERE { MINUS { ?s ?p ?o } }",
                "SELECT * WHERE { FILTER(true) }",
                "SELECT * WHERE { }",
                "SELECT * { }",
                "SELECT * WHERE { GRAPH ?g { } }",
                "SELECT * WHERE { GRAPH <" + A + "> { GRAPH <" + A_PART + "> { ?s ?p ?o } } }",
                "SELECT * WHERE { GRAPH ?g { GRAPH ?h { ?s ?p ?o } } }",
                "SELECT * WHERE { GRAPH \"lit\" { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH _:b { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH [] { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A + "> { ?s ?p ?o } } FROM <http://e/g>",
                "SELECT * FROM <" + A + "> WHERE { ?s ?p ?o }",
                "SELECT * FROM NAMED <" + A + "> WHERE { ?s ?p ?o }",
                "CONSTRUCT { GRAPH <" + A + "> { ?s ?p ?o } } WHERE { ?s ?p ?o }",
                "CONSTRUCT { ?s ?p ?o . ?o ?p ?s } WHERE { ?s ?p ?o }",
                "CONSTRUCT WHERE { ?s ?p ?o FILTER(true) }",
                "CONSTRUCT WHERE { GRAPH ?g { ?s ?p ?o } }",
                "CONSTRUCT { } WHERE { ?s ?p ?o }",
                "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } ORDER BY ?s LIMIT 1",
                "DESCRIBE *",
                "DESCRIBE * WHERE { ?s ?p ?o }",
                "DESCRIBE <http://e/a> <http://e/b> ?x WHERE { ?x ?p ?o }",
                "DESCRIBE ?x",
                "ASK WHERE { ?s ?p ?o }",
                "ASK { ?s ?p ?o } LIMIT 1",
                "ASK { ?s ?p ?o } ORDER BY ?s",
                "ASK { ?s ?p ?o } VALUES ?s { 1 }",
                "SELECT * WHERE { ?s ?p ?o } LIMIT 1 VALUES ?s { 1 }",
                "BASE <http://e/> SELECT * WHERE { ?s ?p <x> }",
                "BASE <http://e/> BASE <http://f/> SELECT * WHERE { ?s ?p <x> }",
                "PREFIX p: <http://e/> BASE <http://f/> PREFIX q: <http://g/> SELECT * WHERE { ?s ?p <x> }",
                "SELECT * WHERE { ?s ?p ?o FILTER(<http://www.w3.org/2005/xpath-functions#concat>(?o, ?o)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER(<http://www.w3.org/2005/xpath-functions#nonexistent>(?o)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER(<http://www.w3.org/2001/XMLSchema#nonexistent>(?o)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER(<http://www.w3.org/2005/xpath-functions/math#nonexistent>(?o)) }",
                "SELECT (<http://www.w3.org/2001/XMLSchema#integer>(<http://jena.apache.org/ARQ/function#sha1sum>(?o)) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (<http://jena.apache.org/ARQ/function/aggregate#x>(?o) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (<http://example.org/agg>(?o) AS ?x) WHERE { ?s ?p ?o } GROUP BY ?s",
                "SELECT (<http://example.org/agg>(DISTINCT ?o) AS ?x) WHERE { ?s ?p ?o } GROUP BY ?s",
                "SELECT (CALL(<http://e/f>, ?o) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (<http://www.w3.org/2005/xpath-functions#upper-case>(<java:x.Y>(?o)) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o FILTER(EXISTS { ?s <java:x.Y> ?z }) }",
                "SELECT * WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#member> ?o }",
                "SELECT * WHERE { ?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#_1> ?o }",
                "SELECT * WHERE { ?s <http://jena.apache.org/ARQ/property#concatenate> ?o }",
                "SELECT * WHERE { ?s <http://jena.apache.org/ARQ/property#splitIRI> ?o }",
                "SELECT * WHERE { ?s <http://jena.apache.org/ARQ/list#index> ?o }",
                "SELECT * WHERE { ?s <http://jena.apache.org/ARQ/list#member>/<" + P + "> ?o }",
                "SELECT * WHERE { ?s !(<http://jena.apache.org/ARQ/list#member>) ?o }",
                "SELECT * WHERE { ?s (<http://jena.apache.org/ARQ/list#member>)* ?o }",
                "SELECT * WHERE { ?s ^<http://jena.apache.org/ARQ/list#member> ?o }",
                "SELECT * WHERE { ?s <http://jena.apache.org/ARQ/property#bag> ?o }",
                "SELECT * WHERE { ?s <http://jena.apache.org/text#query> ?o }",
                "SELECT * WHERE { ?s <JAVA:x.Y> ?o }",
                "SELECT * WHERE { ?s <http://JENA.apache.org/ARQ/list#member> ?o }",
                "SELECT * WHERE { ?s <urn:x-arq:self> ?o }",
                "SELECT * WHERE { ?s <urn:x-arq:DefaultGraph> ?o }",
                "SELECT * WHERE { GRAPH <urn:x-arq:DefaultGraph> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <urn:x-arq:UnionGraph> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A + "#> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A.toUpperCase() + "> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A + "/> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A + "/./> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A_PART + "/../> { ?s ?p ?o } }",
                "SELECT * WHERE { GRAPH <" + A + ">{?s ?p ?o}GRAPH <" + A_PART + ">{?s ?p ?o} }",
                "SELECT * WHERE { ?s ?p ?o \\u0046ILTER(true) }",
                "SELECT * \\u0057HERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p \"\\u0022 }",
                "SELECT * WHERE { ?s ?p \"a\\u005C\" }",
                "SELECT * WHERE { ?s ?p \"\\u005Cu0046\" }",
                "SELECT * \\u005Cu0046ROM <http://e/g> WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p \"\\\\u0046\" }",
                "SELECT * WHERE { ?s ?p \"\\U00000046\" }",
                "SELECT * WHERE { ?s ?p \"\\uD800\" }",
                "SELECT * WHERE { ?s ?p \"\\uD83D\\uDE00\" }",
                "SELECT * WHERE { ?s ?p \"\\u12\" }",
                "SELECT * WHERE { ?s ?p \"\\u12G4\" }",
                "SELECT * WHERE { ?s ?p \"\\U0000004\" }",
                "SELECT * WHERE { ?s ?p \"\\U00110000\" }",
                "SELECT * WHERE { ?s ?p ?o }\u0000",
                "\uFEFFSELECT * WHERE { ?s ?p ?o }",
                "SELECT\u00a0* WHERE { ?s ?p ?o }",
                "SELECT\u2028* WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o }\r\n# c\r\n",
                "SELECT * WHERE { ?s ?p ?\u00e9 }",
                "SELECT * WHERE { ?s ?p ?o1_\u00b7 }",
                "SELECT * WHERE { ?s ?p $o }",
                "SELECT * WHERE { ?s ?p ?o . $s ?p ?o }",
                "SELECT ?s,?o WHERE { ?s ?p ?o }",
                "SELECT (?s AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (?s) WHERE { ?s ?p ?o }",
                "SELECT ?s ?s WHERE { ?s ?p ?o }",
                "SELECT * WHERE { ?s ?p ?o FILTER ?o }",
                "SELECT * WHERE { ?s ?p ?o FILTER (?o) (?s) }",
                "SELECT * WHERE { ?s ?p ?o FILTER REGEX(?o, \"a\") }",
                "SELECT * WHERE { ?s ?p ?o FILTER <http://www.w3.org/2001/XMLSchema#boolean>(?o) }",
                "SELECT * WHERE { ?s ?p ?o FILTER BOUND(?o) FILTER BOUND(?s) }",
                "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { } }",
                "SELECT * WHERE { ?s ?p ?o FILTER (EXISTS { ?s ?p ?o } && NOT EXISTS { ?o ?p ?s }) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (?o IN ()) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (?o IN (1,)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (STRLEN(?o, 1)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (SUBSTR(?o)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (CONCAT()) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (COALESCE()) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (BNODE(1, 2)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (RAND(1)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (NOW(1)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (REGEX(?o)) }",
                "SELECT * WHERE { ?s ?p ?o FILTER (IF(1, 2)) }",
                "SELECT (GROUP_CONCAT(?o; separator=1) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (GROUP_CONCAT(DISTINCT ?o) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (COUNT(DISTINCT *) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (SUM(*) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (SUM() AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (MIN(DISTINCT ?o) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (SAMPLE(DISTINCT ?o) AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (COUNT(?o) + 1 AS ?x) WHERE { ?s ?p ?o }",
                "SELECT (COUNT(?o) AS ?x) (COUNT(?s) AS ?x) WHERE { ?s ?p ?o }"
        );
    }

    @Test
    void parserDisagreementCasesAreRejectedOrHandledConsistently() {
        List<String> report = new ArrayList<>();
        for (String q : disagreementCases()) {
            String text = SparqlQueryGuard.decodeUnicodeEscapes(q);
            String r = outcome(() -> SparqlQueryGuard.checkRdf4j(text, PUBLIC).kind());
            String j = outcome(() -> JenaQueryGuard.check(text, PUBLIC));
            String combined = outcome(() -> SparqlQueryGuard.check(q, PUBLIC).kind());
            boolean rOk = !r.startsWith("REJECT");
            boolean jOk = !j.startsWith("REJECT");
            if (rOk && jOk) {
                assertEquals(r, j, "kinds differ for " + q);
                assertTrue(combined.equals(r) || combined.contains("nested unicode escapes"), q + " ==> " + combined);
            } else {
                assertTrue(combined.startsWith("REJECT"), "combined guard accepted a query one parser rejected: " + q);
            }
            if (rOk != jOk) {
                report.add((rOk ? "RDF4J-only: " : "Jena-only: ") + q.replace("\n", "\\n") + "  => " + (rOk ? j : r));
            }
        }
        report.forEach(System.out::println);
    }

    interface Check {
        Object run();
    }

    static String outcome(Check c) {
        try {
            return String.valueOf(c.run());
        } catch (SparqlQueryRejectedException e) {
            return "REJECT(" + e.getMessage() + ")";
        }
    }

    @Test
    void nestedUnicodeEscapesAreRejected() {
        SparqlQueryRejectedException e = assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryGuard.check("SELECT * \\u005Cu0046ROM <http://e/g> WHERE { ?s ?p ?o }", PUBLIC));
        assertTrue(e.getMessage().contains("nested unicode escapes"));
    }

    @Test
    void escapedKeywordsAreSeenByBothParsersAsTheRealKeyword() {
        assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryGuard.check("SELECT * \\u0046ROM <http://e/g> WHERE { ?s ?p ?o }", PUBLIC));
        assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryGuard.check("SELECT * WHERE { \\u0053ERVICE <http://e/s> { ?s ?p ?o } }", PUBLIC));
        assertEquals(SparqlQueryGuard.Kind.ASK, SparqlQueryGuard.check("\\u0041SK { ?s ?p ?o }", PUBLIC).kind());
    }

    @Test
    void deeplyNestedQueriesAreRejectedNotCrashed() {
        String deep = "SELECT * WHERE " + "{ ".repeat(20000) + "?s ?p ?o" + " }".repeat(20000);
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlQueryGuard.check(deep, PUBLIC));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#member> ?o }"
    })
    void registeredPropertyFunctionsAreRejectedByJena(String q) {
        assertThrows(SparqlQueryRejectedException.class, () -> JenaQueryGuard.check(q, PUBLIC));
    }
}
