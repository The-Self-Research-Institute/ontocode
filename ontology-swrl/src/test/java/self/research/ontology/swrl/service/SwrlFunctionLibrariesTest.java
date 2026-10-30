package self.research.ontology.swrl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.factory.SWRLAPIFactory;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.InferredAxiom;
import self.research.ontology.swrl.model.SkippedRule;
import self.research.ontology.swrl.model.ValidationResult;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SWRL and SQWRL function check (quick subset by default, full set with -Dswrl.functions.full=true)")
class SwrlFunctionLibrariesTest {

    private static final boolean FULL = Boolean.getBoolean("swrl.functions.full");

    private static final Set<String> QUICK_CASES = Set.of("eq_int", "gt_date", "eq_text", "not_bool", "add_int", "temporal_notBefore",
            "temporal_durationGreaterThan_fails", "abox_caa_wrong_order_fails", "unknown_function", "unknown_prefix", "unknown_class");

    private static final Set<String> QUICK_QUERIES = Set.of("select", "count", "sum_int", "avg_int", "min_text_fails", "nthLast_rejected", "abox_caa");

    private static List<Case> active() {
        return FULL ? cases() : cases().stream().filter(c -> QUICK_CASES.contains(c.id())).toList();
    }

    private static List<Query> activeQueries() {
        return FULL ? queries() : queries().stream().filter(q -> QUICK_QUERIES.contains(q.id())).toList();
    }

    private static final String NS = "http://example.org/gh#";

    enum Kind { WORKS, RUN_FAILS, REJECTED }

    record Case(String id, String function, String rule, Kind kind, Set<String> facts, Predicate<Set<String>> check, String message) {
        String ruleText() {
            return rule.replace("HIT(", "Hit_" + id + "(").replace("OUT(", "out_" + id + "(");
        }

        @Override
        public String toString() {
            return function + " [" + id + "]";
        }
    }

    record Query(String id, String function, String text, boolean ordered, List<String> columns, List<String> rows, String message) {
        @Override
        public String toString() {
            return function + " [" + id + "]";
        }
    }

    private static Set<String> set(String... values) {
        return new TreeSet<>(List.of(values));
    }

    private static Case works(String id, String function, String rule, String... facts) {
        return new Case(id, function, rule, Kind.WORKS, set(facts), null, null);
    }

    private static Case worksWhen(String id, String function, String rule, Predicate<Set<String>> check) {
        return new Case(id, function, rule, Kind.WORKS, null, check, null);
    }

    private static Case fails(String id, String function, String rule, String message) {
        return new Case(id, function, rule, Kind.RUN_FAILS, null, null, message);
    }

    private static Case rejected(String id, String function, String rule, String message) {
        return new Case(id, function, rule, Kind.REJECTED, null, null, message);
    }

    private static void both(List<Case> cases, String id, String function, String rule, String... facts) {
        cases.add(works(id + "_typed", function, rule.replace("STR(", "strText("), facts));
        cases.add(works(id + "_plain", function, rule.replace("STR(", "plainText("), facts));
    }

    private static void compare(List<Case> cases, String id, String function, String property, String constant, String... facts) {
        cases.add(works(id, function, "Item(?r) ^ " + property + "(?r, ?v) ^ " + function + "(?v, " + constant + ") -> HIT(?r)", facts));
    }

    private static void calc(List<Case> cases, String id, String function, String property, String call, String... facts) {
        cases.add(works(id, function, "Item(?r) ^ " + property + "(?r, ?v) ^ " + call + " -> OUT(?r, ?o)", facts));
    }

    private static void failing(List<Case> cases, String id, String function, String property, String call, String message) {
        cases.add(fails(id, function, "Item(?r) ^ " + property + "(?r, ?v) ^ " + call + " -> OUT(?r, ?o)", message));
    }

    private static void pureCalc(List<Case> cases, String id, String function, String call, String... facts) {
        cases.add(works(id, function, "Item(?r) ^ " + call + " -> OUT(?r, ?o)", facts));
    }

    private static void pureFails(List<Case> cases, String id, String function, String call, String message) {
        cases.add(fails(id, function, "Item(?r) ^ " + call + " -> OUT(?r, ?o)", message));
    }

    private static Predicate<Set<String>> prefixes(String first, String second) {
        return facts -> facts.size() == 2 && facts.stream().anyMatch(f -> f.startsWith(first)) && facts.stream().anyMatch(f -> f.startsWith(second));
    }

    static List<Case> cases() {
        List<Case> c = new ArrayList<>();
        compare(c, "eq_int", "swrlb:equal", "intVal", "7", "r1");
        compare(c, "ne_int", "swrlb:notEqual", "intVal", "7", "r2");
        compare(c, "lt_int", "swrlb:lessThan", "intVal", "10", "r1");
        compare(c, "le_int", "swrlb:lessThanOrEqual", "intVal", "7", "r1");
        compare(c, "gt_int", "swrlb:greaterThan", "intVal", "10", "r2");
        compare(c, "ge_int", "swrlb:greaterThanOrEqual", "intVal", "12", "r2");
        compare(c, "gt_dec", "swrlb:greaterThan", "decVal", "0", "r1");
        compare(c, "lt_dec", "swrlb:lessThan", "decVal", "0", "r2");
        compare(c, "eq_dec", "swrlb:equal", "decVal", "2.5", "r1");
        compare(c, "gt_dbl", "swrlb:greaterThan", "dblVal", "1.0", "r1");
        compare(c, "le_dbl", "swrlb:lessThanOrEqual", "dblVal", "0.5", "r2");
        compare(c, "eq_dbl_whole", "swrlb:equal", "dblVal", "9", "r1");
        compare(c, "eq_int_decimal_constant", "swrlb:equal", "intVal", "7.0", "r1");
        compare(c, "eq_bool", "swrlb:equal", "flag", "true", "r1");
        compare(c, "ne_bool", "swrlb:notEqual", "flag", "true", "r2");
        compare(c, "eq_date", "swrlb:equal", "day", "\"2024-03-15\"^^xsd:date", "r1");
        compare(c, "lt_date", "swrlb:lessThan", "day", "\"2024-12-31\"^^xsd:date", "r1");
        compare(c, "le_date", "swrlb:lessThanOrEqual", "day", "\"2024-03-15\"^^xsd:date", "r1");
        compare(c, "gt_date", "swrlb:greaterThan", "day", "\"2024-06-01\"^^xsd:date", "r2");
        compare(c, "ge_date", "swrlb:greaterThanOrEqual", "day", "\"2025-01-01\"^^xsd:date", "r2");
        compare(c, "eq_datetime", "swrlb:equal", "stamp", "\"2024-03-15T10:30:00\"^^xsd:dateTime", "r1");
        compare(c, "lt_datetime", "swrlb:lessThan", "stamp", "\"2024-06-01T00:00:00\"^^xsd:dateTime", "r1");
        compare(c, "gt_datetime", "swrlb:greaterThan", "stamp", "\"2024-06-01T00:00:00\"^^xsd:dateTime", "r2");
        compare(c, "eq_time", "swrlb:equal", "clock", "\"10:30:00\"^^xsd:time", "r1");
        compare(c, "known_lt_time_matches_every_time", "swrlb:lessThan", "clock", "\"12:00:00\"^^xsd:time", "r1", "r2");
        compare(c, "int_never_equals_text", "swrlb:equal", "intVal", "\"7\"");
        compare(c, "date_never_equals_plain_text", "swrlb:equal", "day", "\"2024-03-15\"");
        both(c, "eq_text", "swrlb:equal", "Item(?r) ^ STR(?r, ?v) ^ swrlb:equal(?v, \"Alpha Beta\") -> HIT(?r)", "r1");
        both(c, "eq_text_typed_constant", "swrlb:equal", "Item(?r) ^ STR(?r, ?v) ^ swrlb:equal(?v, \"Alpha Beta\"^^xsd:string) -> HIT(?r)", "r1");
        both(c, "ne_text", "swrlb:notEqual", "Item(?r) ^ STR(?r, ?v) ^ swrlb:notEqual(?v, \"Alpha Beta\") -> HIT(?r)", "r2");
        both(c, "lt_text", "swrlb:lessThan", "Item(?r) ^ STR(?r, ?v) ^ swrlb:lessThan(?v, \"b\") -> HIT(?r)", "r1");
        both(c, "le_text", "swrlb:lessThanOrEqual", "Item(?r) ^ STR(?r, ?v) ^ swrlb:lessThanOrEqual(?v, \"Alpha Beta\") -> HIT(?r)", "r1");
        both(c, "gt_text", "swrlb:greaterThan", "Item(?r) ^ STR(?r, ?v) ^ swrlb:greaterThan(?v, \"b\") -> HIT(?r)", "r2");
        both(c, "ge_text", "swrlb:greaterThanOrEqual", "Item(?r) ^ STR(?r, ?v) ^ swrlb:greaterThanOrEqual(?v, \"gamma\") -> HIT(?r)", "r2");
        c.add(works("not_bool", "swrlb:booleanNot", "Item(?r) ^ flag(?r, ?v) ^ swrlb:booleanNot(?o, ?v) -> OUT(?r, ?o)", "r1=false", "r2=true"));
        c.add(works("not_bool_check", "swrlb:booleanNot", "Item(?r) ^ flag(?r, ?v) ^ swrlb:booleanNot(false, ?v) -> HIT(?r)", "r1"));

        calc(c, "add_int", "swrlb:add", "intVal", "swrlb:add(?o, ?v, 5)", "r1=12", "r2=17");
        calc(c, "add_dec", "swrlb:add", "decVal", "swrlb:add(?o, ?v, 1)", "r1=3.5", "r2=-2.5");
        calc(c, "add_dbl", "swrlb:add", "dblVal", "swrlb:add(?o, ?v, 1)", "r1=10.0", "r2=1.5");
        calc(c, "add_three_values", "swrlb:add", "intVal", "swrlb:add(?o, ?v, ?v, 1)", "r1=15", "r2=25");
        c.add(works("add_two_properties", "swrlb:add", "Item(?r) ^ intVal(?r, ?v) ^ decVal(?r, ?d) ^ swrlb:add(?o, ?v, ?d) -> OUT(?r, ?o)", "r1=9.5", "r2=8.5"));
        calc(c, "subtract_int", "swrlb:subtract", "intVal", "swrlb:subtract(?o, ?v, 5)", "r1=2", "r2=7");
        calc(c, "subtract_dbl", "swrlb:subtract", "dblVal", "swrlb:subtract(?o, ?v, 0.5)", "r1=8.5", "r2=0.0");
        calc(c, "multiply_int", "swrlb:multiply", "intVal", "swrlb:multiply(?o, ?v, 3)", "r1=21", "r2=36");
        calc(c, "known_divide_int_rounds_to_whole", "swrlb:divide", "intVal", "swrlb:divide(?o, ?v, 2)", "r1=4", "r2=6");
        calc(c, "known_divide_dec_rounds_to_one_place", "swrlb:divide", "decVal", "swrlb:divide(?o, ?v, 2)", "r1=1.3", "r2=-1.8");
        calc(c, "divide_dbl", "swrlb:divide", "dblVal", "swrlb:divide(?o, ?v, 3)", "r1=3.0", "r2=0.2");
        calc(c, "known_integerDivide_adds_instead", "swrlb:integerDivide", "intVal", "swrlb:integerDivide(?o, ?v, 5)", "r1=12", "r2=17");
        calc(c, "mod_int", "swrlb:mod", "intVal", "swrlb:mod(?o, ?v, 5)", "r1=2", "r2=2");
        calc(c, "pow_int", "swrlb:pow", "intVal", "swrlb:pow(?o, ?v, 2)", "r1=49", "r2=144");
        calc(c, "unaryPlus_int", "swrlb:unaryPlus", "intVal", "swrlb:unaryPlus(?o, ?v)", "r1=7", "r2=12");
        calc(c, "unaryMinus_int", "swrlb:unaryMinus", "intVal", "swrlb:unaryMinus(?o, ?v)", "r1=-7", "r2=-12");
        calc(c, "abs_dec", "swrlb:abs", "decVal", "swrlb:abs(?o, ?v)", "r1=2.5", "r2=3.5");
        calc(c, "ceiling_dec", "swrlb:ceiling", "decVal", "swrlb:ceiling(?o, ?v)", "r1=3", "r2=-3");
        calc(c, "floor_dec", "swrlb:floor", "decVal", "swrlb:floor(?o, ?v)", "r1=2", "r2=-4");
        calc(c, "round_int", "swrlb:round", "intVal", "swrlb:round(?o, ?v)", "r1=7", "r2=12");
        failing(c, "round_dec_fails", "swrlb:round", "decVal", "swrlb:round(?o, ?v)", "cannot round");
        failing(c, "round_dbl_fails", "swrlb:round", "dblVal", "swrlb:round(?o, ?v)", "cannot round");
        calc(c, "roundHalfToEven_dec", "swrlb:roundHalfToEven", "decVal", "swrlb:roundHalfToEven(?o, ?v)", "r1=2", "r2=-4");
        calc(c, "roundHalfToEven_dbl", "swrlb:roundHalfToEven", "dblVal", "swrlb:roundHalfToEven(?o, ?v)", "r1=9.0", "r2=0.0");
        failing(c, "roundHalfToEven_precision_arity", "swrlb:roundHalfToEven", "decVal", "swrlb:roundHalfToEven(?o, ?v, 0)", "needs 2 values");
        calc(c, "sin_dbl", "swrlb:sin", "dblVal", "swrlb:sin(?o, ?v)", "r1=" + Math.sin(9.0), "r2=" + Math.sin(0.5));
        calc(c, "cos_dbl", "swrlb:cos", "dblVal", "swrlb:cos(?o, ?v)", "r1=" + Math.cos(9.0), "r2=" + Math.cos(0.5));
        calc(c, "tan_dbl", "swrlb:tan", "dblVal", "swrlb:tan(?o, ?v)", "r1=" + Math.tan(9.0), "r2=" + Math.tan(0.5));
        failing(c, "add_text_fails", "swrlb:add", "strText", "swrlb:add(?o, ?v, 1)", "needs a decimal number");
        failing(c, "add_arity_fails", "swrlb:add", "intVal", "swrlb:add(?o)", "needs at least 2 values");
        c.add(fails("add_unbound_fails", "swrlb:add", "Item(?r) ^ swrlb:add(?o, ?x, 1) -> OUT(?r, ?o)", "only the first value may be a new variable"));
        c.add(fails("compare_unbound_fails", "swrlb:greaterThan", "Item(?r) ^ swrlb:greaterThan(?x, 1) -> HIT(?r)", "no earlier condition gives a value"));
        failing(c, "divide_by_zero_fails", "swrlb:divide", "intVal", "swrlb:divide(?o, ?v, 0)", "divides by zero");
        c.add(fails("equal_on_individuals_fails", "swrlb:equal", "Item(?r) ^ swrlb:equal(?r, ?r) -> HIT(?r)", "expecting literal, got individual"));

        both(c, "stringEqualIgnoreCase", "swrlb:stringEqualIgnoreCase", "Item(?r) ^ STR(?r, ?v) ^ swrlb:stringEqualIgnoreCase(?v, \"ALPHA beta\") -> HIT(?r)", "r1");
        both(c, "stringConcat", "swrlb:stringConcat", "Item(?r) ^ STR(?r, ?v) ^ swrlb:stringConcat(?o, ?v, \"-\", ?v) -> OUT(?r, ?o)", "r1=Alpha Beta-Alpha Beta", "r2=gamma-gamma");
        c.add(works("stringConcat_typed_and_plain", "swrlb:stringConcat", "Item(?r) ^ strText(?r, ?a) ^ plainText(?r, ?b) ^ swrlb:stringConcat(?o, ?a, \"|\", ?b) -> OUT(?r, ?o)", "r1=Alpha Beta|Alpha Beta", "r2=gamma|gamma"));
        c.add(works("stringConcat_text_and_number", "swrlb:stringConcat", "Item(?r) ^ strText(?r, ?a) ^ intVal(?r, ?b) ^ swrlb:stringConcat(?o, ?a, ?b) -> OUT(?r, ?o)", "r1=Alpha Beta7", "r2=gamma12"));
        both(c, "known_substring_zero_based_end_index", "swrlb:substring", "Item(?r) ^ STR(?r, ?v) ^ swrlb:substring(?o, ?v, 2, 3) -> OUT(?r, ?o)", "r1=p", "r2=m");
        failing(c, "substring_past_end_fails", "swrlb:substring", "strText", "swrlb:substring(?o, ?v, 7)", "StringIndexOutOfBoundsException");
        failing(c, "substring_arity_fails", "swrlb:substring", "strText", "swrlb:substring(?o, ?v)", "needs at least 3 values");
        both(c, "stringLength", "swrlb:stringLength", "Item(?r) ^ STR(?r, ?v) ^ swrlb:stringLength(?o, ?v) -> OUT(?r, ?o)", "r1=10", "r2=5");
        both(c, "stringLength_as_test", "swrlb:stringLength", "Item(?r) ^ STR(?r, ?v) ^ swrlb:stringLength(5, ?v) -> HIT(?r)", "r2");
        c.add(works("normalizeSpace", "swrlb:normalizeSpace", "Item(?r) ^ spaced(?r, ?v) ^ swrlb:normalizeSpace(?o, ?v) -> OUT(?r, ?o)", "r1=a b", "r2=x"));
        both(c, "normalizeSpace_text", "swrlb:normalizeSpace", "Item(?r) ^ STR(?r, ?v) ^ swrlb:normalizeSpace(?o, ?v) -> OUT(?r, ?o)", "r1=Alpha Beta", "r2=gamma");
        both(c, "upperCase", "swrlb:upperCase", "Item(?r) ^ STR(?r, ?v) ^ swrlb:upperCase(?o, ?v) -> OUT(?r, ?o)", "r1=ALPHA BETA", "r2=GAMMA");
        both(c, "lowerCase", "swrlb:lowerCase", "Item(?r) ^ STR(?r, ?v) ^ swrlb:lowerCase(?o, ?v) -> OUT(?r, ?o)", "r1=alpha beta", "r2=gamma");
        both(c, "translate_one_character", "swrlb:translate", "Item(?r) ^ STR(?r, ?v) ^ swrlb:translate(?o, ?v, \"a\", \"x\") -> OUT(?r, ?o)", "r1=Alphx Betx", "r2=gxmmx");
        both(c, "known_translate_longer_map_unchanged", "swrlb:translate", "Item(?r) ^ STR(?r, ?v) ^ swrlb:translate(?o, ?v, \"ab\", \"xy\") -> OUT(?r, ?o)", "r1=Alpha Beta", "r2=gamma");
        both(c, "contains", "swrlb:contains", "Item(?r) ^ STR(?r, ?v) ^ swrlb:contains(?v, \"pha\") -> HIT(?r)", "r1");
        both(c, "containsIgnoreCase", "swrlb:containsIgnoreCase", "Item(?r) ^ STR(?r, ?v) ^ swrlb:containsIgnoreCase(?v, \"PHA\") -> HIT(?r)", "r1");
        both(c, "startsWith", "swrlb:startsWith", "Item(?r) ^ STR(?r, ?v) ^ swrlb:startsWith(?v, \"Alp\") -> HIT(?r)", "r1");
        both(c, "endsWith", "swrlb:endsWith", "Item(?r) ^ STR(?r, ?v) ^ swrlb:endsWith(?v, \"eta\") -> HIT(?r)", "r1");
        both(c, "substringBefore", "swrlb:substringBefore", "Item(?r) ^ STR(?r, ?v) ^ swrlb:substringBefore(?o, ?v, \" \") -> OUT(?r, ?o)", "r1=Alpha", "r2=gamma");
        both(c, "substringAfter", "swrlb:substringAfter", "Item(?r) ^ STR(?r, ?v) ^ swrlb:substringAfter(?o, ?v, \" \") -> OUT(?r, ?o)", "r1=Beta", "r2=");
        both(c, "matches", "swrlb:matches", "Item(?r) ^ STR(?r, ?v) ^ swrlb:matches(?v, \"^Alp.*a$\") -> HIT(?r)", "r1");
        c.add(fails("matches_flags_arity_fails", "swrlb:matches", "Item(?r) ^ strText(?r, ?v) ^ swrlb:matches(?v, \"^alpha\", \"i\") -> HIT(?r)", "needs 2 values"));
        c.add(fails("matches_bad_pattern_fails", "swrlb:matches", "Item(?r) ^ strText(?r, ?v) ^ swrlb:matches(?v, \"([\") -> HIT(?r)", "not a valid regular expression"));
        both(c, "replace", "swrlb:replace", "Item(?r) ^ STR(?r, ?v) ^ swrlb:replace(?o, ?v, \"a\", \"o\") -> OUT(?r, ?o)", "r1=Alpho Beto", "r2=gommo");
        both(c, "tokenize", "swrlb:tokenize", "Item(?r) ^ STR(?r, ?v) ^ swrlb:tokenize(?o, ?v, \" \") -> OUT(?r, ?o)", "r1=Alpha", "r1=Beta", "r2=gamma");
        failing(c, "upperCase_on_number_fails", "swrlb:upperCase", "intVal", "swrlb:upperCase(?o, ?v)", "needs text");
        c.add(works("several_functions_in_one_rule", "swrlb:add + swrlb:greaterThan + swrlb:upperCase + swrlb:startsWith",
                "Item(?r) ^ intVal(?r, ?v) ^ decVal(?r, ?d) ^ strText(?r, ?s) ^ swrlb:greaterThan(?v, 5) ^ swrlb:add(?t, ?v, ?d) ^ swrlb:upperCase(?u, ?s) ^ swrlb:startsWith(?u, \"ALPHA\") -> OUT(?r, ?t)",
                "r1=9.5"));
        c.add(works("several_functions_on_plain_text", "swrlb:stringConcat + swrlb:stringLength + swrlb:greaterThan",
                "Item(?r) ^ plainText(?r, ?s) ^ swrlb:stringConcat(?c, ?s, \"!\") ^ swrlb:stringLength(?n, ?c) ^ swrlb:greaterThan(?n, 6) -> OUT(?r, ?c)",
                "r1=Alpha Beta!"));

        pureCalc(c, "yearMonthDuration", "swrlb:yearMonthDuration", "swrlb:yearMonthDuration(?o, 1, 6)", "r1=P1Y6M", "r2=P1Y6M");
        pureCalc(c, "dayTimeDuration", "swrlb:dayTimeDuration", "swrlb:dayTimeDuration(?o, 2, 12, 0, 0)", "r1=P2DT12H", "r2=P2DT12H");
        pureCalc(c, "known_date_wrong_form", "swrlb:date", "swrlb:date(?o, 2024, 3, 15, \"\")", "r1=2024-3-15GMT", "r2=2024-3-15GMT");
        pureCalc(c, "known_dateTime_month_shifted", "swrlb:dateTime", "swrlb:dateTime(?o, 2024, 12, 25, 8, 5, 3, \"Z\")", "r1=2025-0-25T8:5:3GMT", "r2=2025-0-25T8:5:3GMT");
        pureFails(c, "time_fails", "swrlb:time", "swrlb:time(?o, 10, 30, 0, \"Z\")", "could not work out a result");
        pureFails(c, "date_arity_fails", "swrlb:date", "swrlb:date(?o, 2024, 3, 15)", "needs 5 values");
        pureCalc(c, "addDayTimeDurations", "swrlb:addDayTimeDurations", "swrlb:dayTimeDuration(?a, 1, 0, 0, 0) ^ swrlb:dayTimeDuration(?b, 0, 12, 0, 0) ^ swrlb:addDayTimeDurations(?o, ?a, ?b)", "r1=P1DT12H", "r2=P1DT12H");
        pureCalc(c, "known_addYearMonthDurations_wrong", "swrlb:addYearMonthDurations", "swrlb:yearMonthDuration(?a, 1, 6) ^ swrlb:yearMonthDuration(?b, 0, 8) ^ swrlb:addYearMonthDurations(?o, ?a, ?b)", "r1=PT8H", "r2=PT8H");
        pureFails(c, "subtractYearMonthDurations_fails", "swrlb:subtractYearMonthDurations", "swrlb:yearMonthDuration(?a, 1, 6) ^ swrlb:yearMonthDuration(?b, 0, 8) ^ swrlb:subtractYearMonthDurations(?o, ?a, ?b)", "not in the form the function expects");
        pureFails(c, "subtractDayTimeDurations_fails", "swrlb:subtractDayTimeDurations", "swrlb:dayTimeDuration(?a, 1, 0, 0, 0) ^ swrlb:dayTimeDuration(?b, 0, 12, 0, 0) ^ swrlb:subtractDayTimeDurations(?o, ?a, ?b)", "not in the form the function expects");
        pureFails(c, "multiplyYearMonthDuration_fails", "swrlb:multiplyYearMonthDuration", "swrlb:yearMonthDuration(?a, 1, 6) ^ swrlb:multiplyYearMonthDuration(?o, ?a, 2)", "but the function needs a date or time");
        pureFails(c, "divideYearMonthDuration_fails", "swrlb:divideYearMonthDuration", "swrlb:yearMonthDuration(?a, 1, 6) ^ swrlb:divideYearMonthDuration(?o, ?a, 2)", "but the function needs a date or time");
        pureFails(c, "multiplyDayTimeDuration_fails", "swrlb:multiplyDayTimeDuration", "swrlb:dayTimeDuration(?a, 1, 0, 0, 0) ^ swrlb:multiplyDayTimeDuration(?o, ?a, 2)", "but the function needs a date or time");
        pureFails(c, "divideDayTimeDuration_fails", "swrlb:divideDayTimeDuration", "swrlb:dayTimeDuration(?a, 1, 0, 0, 0) ^ swrlb:divideDayTimeDuration(?o, ?a, 2)", "but the function needs a date or time");
        failing(c, "subtractDates_fails", "swrlb:subtractDates", "day", "swrlb:subtractDates(?o, ?v, \"2024-03-01\"^^xsd:date)", "not in the form the function expects");
        failing(c, "subtractTimes_fails", "swrlb:subtractTimes", "clock", "swrlb:subtractTimes(?o, ?v, \"08:00:00\"^^xsd:time)", "needs text");
        failing(c, "subtractDateTimesYieldingYearMonthDuration_fails", "swrlb:subtractDateTimesYieldingYearMonthDuration", "stamp", "swrlb:subtractDateTimesYieldingYearMonthDuration(?o, ?v, \"2023-03-15T10:30:00\"^^xsd:dateTime)", "not in the form the function expects");
        c.add(rejected("subtractDateTimesYieldingDayTimeDuration_unknown", "swrlb:subtractDateTimesYieldingDayTimeDuration", "Item(?r) ^ stamp(?r, ?v) ^ swrlb:subtractDateTimesYieldingDayTimeDuration(?o, ?v, \"2024-03-14T10:30:00\"^^xsd:dateTime) -> OUT(?r, ?o)", "Unknown function"));
        c.add(works("addYearMonthDurationToDate", "swrlb:addYearMonthDurationToDate", "Item(?r) ^ day(?r, ?d) ^ swrlb:yearMonthDuration(?a, 0, 2) ^ swrlb:addYearMonthDurationToDate(?o, ?d, ?a) -> OUT(?r, ?o)", "r1=2024-05-15", "r2=2025-03-01"));
        c.add(worksWhen("known_addYearMonthDurationToDateTime_time_of_day_replaced", "swrlb:addYearMonthDurationToDateTime", "Item(?r) ^ stamp(?r, ?d) ^ swrlb:yearMonthDuration(?a, 0, 2) ^ swrlb:addYearMonthDurationToDateTime(?o, ?d, ?a) -> OUT(?r, ?o)", prefixes("r1=2024-05-15T", "r2=2025-03-01T")));
        c.add(works("addDayTimeDurationToDate", "swrlb:addDayTimeDurationToDate", "Item(?r) ^ day(?r, ?d) ^ swrlb:dayTimeDuration(?a, 3, 0, 0, 0) ^ swrlb:addDayTimeDurationToDate(?o, ?d, ?a) -> OUT(?r, ?o)", "r1=2024-03-18", "r2=2025-01-04"));
        c.add(works("addDayTimeDurationToDateTime", "swrlb:addDayTimeDurationToDateTime", "Item(?r) ^ stamp(?r, ?d) ^ swrlb:dayTimeDuration(?a, 0, 2, 0, 0) ^ swrlb:addDayTimeDurationToDateTime(?o, ?d, ?a) -> OUT(?r, ?o)", "r1=2024-03-15T12:30:00", "r2=2025-01-01T02:00:00"));
        c.add(fails("addDayTimeDurationToTime_fails", "swrlb:addDayTimeDurationToTime", "Item(?r) ^ clock(?r, ?d) ^ swrlb:dayTimeDuration(?a, 0, 2, 0, 0) ^ swrlb:addDayTimeDurationToTime(?o, ?d, ?a) -> OUT(?r, ?o)", "needs text"));
        c.add(works("subtractYearMonthDurationFromDate", "swrlb:subtractYearMonthDurationFromDate", "Item(?r) ^ day(?r, ?d) ^ swrlb:yearMonthDuration(?a, 0, 2) ^ swrlb:subtractYearMonthDurationFromDate(?o, ?d, ?a) -> OUT(?r, ?o)", "r1=2024-01-15", "r2=2024-11-01"));
        c.add(worksWhen("known_subtractYearMonthDurationFromDateTime_time_of_day_replaced", "swrlb:subtractYearMonthDurationFromDateTime", "Item(?r) ^ stamp(?r, ?d) ^ swrlb:yearMonthDuration(?a, 0, 2) ^ swrlb:subtractYearMonthDurationFromDateTime(?o, ?d, ?a) -> OUT(?r, ?o)", prefixes("r1=2024-01-15T", "r2=2024-11-01T")));
        c.add(works("subtractDayTimeDurationFromDate", "swrlb:subtractDayTimeDurationFromDate", "Item(?r) ^ day(?r, ?d) ^ swrlb:dayTimeDuration(?a, 3, 0, 0, 0) ^ swrlb:subtractDayTimeDurationFromDate(?o, ?d, ?a) -> OUT(?r, ?o)", "r1=2024-03-12", "r2=2024-12-29"));
        c.add(works("subtractDayTimeDurationFromDateTime", "swrlb:subtractDayTimeDurationFromDateTime", "Item(?r) ^ stamp(?r, ?d) ^ swrlb:dayTimeDuration(?a, 0, 2, 0, 0) ^ swrlb:subtractDayTimeDurationFromDateTime(?o, ?d, ?a) -> OUT(?r, ?o)", "r1=2024-03-15T08:30:00", "r2=2024-12-31T10:00:00"));
        c.add(fails("subtractDayTimeDurationFromTime_fails", "swrlb:subtractDayTimeDurationFromTime", "Item(?r) ^ clock(?r, ?d) ^ swrlb:dayTimeDuration(?a, 0, 2, 0, 0) ^ swrlb:subtractDayTimeDurationFromTime(?o, ?d, ?a) -> OUT(?r, ?o)", "needs text"));

        String notImplemented = "does not implement it yet";
        pureFails(c, "anyURI_not_implemented", "swrlb:anyURI", "swrlb:anyURI(?o, \"http\", \"example.org\", \"/a\", \"\", \"\")", notImplemented);
        pureFails(c, "resolveURI_not_implemented", "swrlb:resolveURI", "swrlb:resolveURI(?o, \"b/c\", \"http://example.org/a/\")", notImplemented);
        for (String list : List.of("length", "first", "rest")) {
            c.add(fails(list + "_not_implemented", "swrlb:" + list, "Item(?r) ^ strText(?r, ?v) ^ swrlb:tokenize(?l, ?v, \" \") ^ swrlb:" + list + "(?o, ?l) -> OUT(?r, ?o)", notImplemented));
        }
        c.add(fails("empty_not_implemented", "swrlb:empty", "Item(?r) ^ strText(?r, ?v) ^ swrlb:tokenize(?l, ?v, \" \") ^ swrlb:empty(?l) -> HIT(?r)", notImplemented));
        c.add(fails("member_not_implemented", "swrlb:member", "Item(?r) ^ strText(?r, ?v) ^ swrlb:tokenize(?l, ?v, \" \") ^ swrlb:member(\"Beta\", ?l) -> HIT(?r)", notImplemented));
        c.add(fails("sublist_not_implemented", "swrlb:sublist", "Item(?r) ^ strText(?r, ?v) ^ swrlb:tokenize(?l, ?v, \" \") ^ swrlb:sublist(?o, ?l, 0, 1) -> OUT(?r, ?o)", notImplemented));
        for (String list : List.of("listConcat", "listIntersection", "listSubtraction")) {
            c.add(fails(list + "_not_implemented", "swrlb:" + list, "Item(?r) ^ strText(?r, ?v) ^ swrlb:tokenize(?l, ?v, \" \") ^ swrlb:" + list + "(?o, ?l, ?l) -> OUT(?r, ?o)", notImplemented));
        }

        calc(c, "swrlm_sqrt_result_is_precise", "swrlm:sqrt", "dblVal", "swrlm:sqrt(?o, ?v)", "r1=3.0", "r2=0.7071067811865475244008443621048490392848359376884740365883398689953662392310535194251937671638207864");
        c.add(worksWhen("swrlm_log_is_base_ten", "swrlm:log", "Item(?r) ^ dblVal(?r, ?v) ^ swrlm:log(?o, ?v) -> OUT(?r, ?o)", facts -> facts.size() == 2
                && facts.stream().anyMatch(f -> f.startsWith("r1=0.95424250943")) && facts.stream().anyMatch(f -> f.startsWith("r2=-0.30102999566"))));
        calc(c, "swrlm_eval_formula", "swrlm:eval", "dblVal", "swrlm:eval(?o, \"v*2+1\", ?v)", "r1=19.0", "r2=2.0");
        calc(c, "swrlm_eval_function_in_formula", "swrlm:eval", "dblVal", "swrlm:eval(?o, \"sqrt(v)\", ?v)", "r1=3.0", "r2=" + Math.sqrt(0.5));
        failing(c, "swrlm_eval_needs_double_data", "swrlm:eval", "decVal", "swrlm:eval(?o, \"v*2+1\", ?v)", "xsd:double");
        failing(c, "swrlm_eval_wrong_variable_name", "swrlm:eval", "dblVal", "swrlm:eval(?o, \"x*2+1\", ?v)", "swrlm:eval could not run");
        rejectedFunction(c, "swrlm_exp_unknown", "swrlm:exp", "dblVal", "swrlm:exp(?o, ?v)");
        rejectedFunction(c, "swrlm_log10_unknown", "swrlm:log10", "dblVal", "swrlm:log10(?o, ?v)");
        rejectedFunction(c, "swrlm_sin_unknown", "swrlm:sin", "dblVal", "swrlm:sin(?o, ?v)");
        rejectedFunction(c, "swrlm_toDegrees_unknown", "swrlm:toDegrees", "dblVal", "swrlm:toDegrees(?o, ?v)");

        c.add(worksWhen("swrlx_makeOWLThing", "swrlx:makeOWLThing", "Item(?r) ^ swrlx:makeOWLThing(?x, ?r) -> HIT(?x)", facts -> facts.size() == 2));
        c.add(worksWhen("swrlx_createOWLThing", "swrlx:createOWLThing", "Item(?r) ^ swrlx:createOWLThing(?x, ?r) -> HIT(?x)", facts -> facts.size() == 2));
        c.add(worksWhen("swrlx_invokeSWRLBuiltIn", "swrlx:invokeSWRLBuiltIn", "Item(?r) ^ swrlx:makeOWLThing(?x, ?r) ^ swrlx:invokeSWRLBuiltIn(\"swrlb:greaterThan\", 2, 1) -> HIT(?x)", facts -> facts.size() == 2));
        c.add(fails("swrlx_makeOWLClass_fails", "swrlx:makeOWLClass", "Item(?r) ^ swrlx:makeOWLClass(?x, ?r, Other) -> Other(?x)", "expecting OWL named individual"));

        calc(c, "temporal_add", "temporal:add", "stamp", "temporal:add(?o, ?v, 1, \"Days\")", "r1=2024-03-16T10:30:00.0", "r2=2025-01-02T00:00:00.0");
        calc(c, "temporal_duration", "temporal:duration", "stamp", "temporal:duration(?o, ?v, \"2024-03-18T10:30:00\", \"Days\")", "r1=3", "r2=289");
        compare(c, "temporal_before", "temporal:before", "stamp", "\"2024-12-31T00:00:00\", \"Days\"", "r1");
        compare(c, "temporal_after", "temporal:after", "stamp", "\"2024-12-31T00:00:00\", \"Days\"", "r2");
        compare(c, "temporal_equals", "temporal:equals", "stamp", "\"2024-03-15T10:30:00\", \"Days\"", "r1");
        compare(c, "temporal_notBefore", "temporal:notBefore", "stamp", "\"2024-12-31T00:00:00\", \"Days\"", "r2");
        c.add(fails("temporal_durationGreaterThan_fails", "temporal:durationGreaterThan", "Item(?r) ^ stamp(?r, ?v) ^ temporal:durationGreaterThan(?v, \"2024-03-10T10:30:00\", 3, \"Days\") -> HIT(?r)", "needs a whole number"));
        c.add(fails("temporal_durationEqualTo_fails", "temporal:durationEqualTo", "Item(?r) ^ stamp(?r, ?v) ^ temporal:durationEqualTo(?v, \"2024-03-12T10:30:00\", 3, \"Days\") -> HIT(?r)", "needs a whole number"));

        c.add(fails("abox_caa_wrong_order_fails", "abox:caa", "Item(?r) ^ abox:caa(?r, Item) -> HIT(?r)", "expecting class"));

        c.add(rejected("unknown_function", "swrlb:noSuchThing", "Item(?r) ^ swrlb:noSuchThing(?r, 1) -> HIT(?r)", "Unknown function 'swrlb:noSuchThing'"));
        c.add(rejected("unknown_prefix", "foo:bar", "Item(?r) ^ foo:bar(?r, 1) -> HIT(?r)", "prefix foo:"));
        c.add(rejected("unknown_class", "Nonexistent", "Item(?r) ^ Nonexistent(?r) -> HIT(?r)", "Unknown class or property"));
        return c;
    }

    private static void rejectedFunction(List<Case> cases, String id, String function, String property, String call) {
        cases.add(rejected(id, function, "Item(?r) ^ " + property + "(?r, ?v) ^ " + call + " -> OUT(?r, ?o)", "Unknown function '" + function + "'"));
    }

    private static Query query(String id, String function, String text, boolean ordered, String columns, String... rows) {
        return new Query(id, function, text, ordered, List.of(columns.split(",")), List.of(rows), null);
    }

    private static Query queryFails(String id, String function, String text, String message) {
        return new Query(id, function, text, false, null, null, message);
    }

    static List<Query> queries() {
        List<Query> q = new ArrayList<>();
        q.add(query("select", "sqwrl:select", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v)", false, "r,v", "r=r1;v=7", "r=r2;v=12"));
        q.add(query("selectDistinct", "sqwrl:selectDistinct", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:selectDistinct(?v)", false, "v", "v=7", "v=12"));
        q.add(query("orderBy", "sqwrl:orderBy", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v)", true, "r,v", "r=r1;v=7", "r=r2;v=12"));
        q.add(query("orderByDescending", "sqwrl:orderByDescending", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderByDescending(?v)", true, "r,v", "r=r2;v=12", "r=r1;v=7"));
        q.add(query("orderBy_plain_text", "sqwrl:orderBy", "Item(?r) ^ plainText(?r, ?p) -> sqwrl:select(?r, ?p) ^ sqwrl:orderBy(?p)", true, "r,p", "r=r1;p=Alpha Beta", "r=r2;p=gamma"));
        q.add(query("orderByDescending_typed_text", "sqwrl:orderByDescending", "Item(?r) ^ strText(?r, ?p) -> sqwrl:select(?r, ?p) ^ sqwrl:orderByDescending(?p)", true, "r,p", "r=r2;p=gamma", "r=r1;p=Alpha Beta"));
        q.add(query("orderBy_date", "sqwrl:orderBy", "Item(?r) ^ day(?r, ?p) -> sqwrl:select(?r, ?p) ^ sqwrl:orderBy(?p)", true, "r,p", "r=r1;p=2024-03-15", "r=r2;p=2025-01-01"));
        q.add(query("count", "sqwrl:count", "Item(?r) -> sqwrl:count(?r)", false, "count(?r)", "count(?r)=2"));
        q.add(query("count_nothing_gives_no_row", "sqwrl:count", "Other(?r) -> sqwrl:count(?r)", false, "count(?r)"));
        q.add(query("count_after_filter_gives_no_row", "sqwrl:count", "Item(?r) ^ intVal(?r, ?v) ^ swrlb:greaterThan(?v, 100) -> sqwrl:count(?r)", false, "count(?r)"));
        q.add(queryFails("countDistinct_unknown", "sqwrl:countDistinct", "Item(?r) ^ flag(?r, ?f) -> sqwrl:countDistinct(?f)", "sqwrl:countDistinct is not a function the rule engine knows"));
        q.add(query("sum_int", "sqwrl:sum", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:sum(?v)", false, "sum(?v)", "sum(?v)=19.0"));
        q.add(query("sum_decimal", "sqwrl:sum", "Item(?r) ^ decVal(?r, ?v) -> sqwrl:sum(?v)", false, "sum(?v)", "sum(?v)=-1.0"));
        q.add(query("sum_double", "sqwrl:sum", "Item(?r) ^ dblVal(?r, ?v) -> sqwrl:sum(?v)", false, "sum(?v)", "sum(?v)=9.5"));
        q.add(query("avg_int", "sqwrl:avg", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:avg(?v)", false, "avg(?v)", "avg(?v)=9.5"));
        q.add(query("avg_double", "sqwrl:avg", "Item(?r) ^ dblVal(?r, ?v) -> sqwrl:avg(?v)", false, "avg(?v)", "avg(?v)=4.75"));
        q.add(query("median_int", "sqwrl:median", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:median(?v)", false, "median(?v)", "median(?v)=9.5"));
        q.add(query("median_double", "sqwrl:median", "Item(?r) ^ dblVal(?r, ?v) -> sqwrl:median(?v)", false, "median(?v)", "median(?v)=4.75"));
        q.add(query("min_int", "sqwrl:min", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:min(?v)", false, "min(?v)", "min(?v)=7"));
        q.add(query("max_int", "sqwrl:max", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:max(?v)", false, "max(?v)", "max(?v)=12"));
        q.add(queryFails("min_text_fails", "sqwrl:min", "Item(?r) ^ plainText(?r, ?p) -> sqwrl:min(?p)", "sqwrl:min could not run"));
        q.add(queryFails("max_date_fails", "sqwrl:max", "Item(?r) ^ day(?r, ?p) -> sqwrl:max(?p)", "sqwrl:max could not run"));
        q.add(query("columnNames", "sqwrl:columnNames", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:columnNames(\"Item\", \"Value\")", false, "Item,Value", "Item=r1;Value=7", "Item=r2;Value=12"));
        q.add(query("limit", "sqwrl:limit", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:limit(1)", true, "r,v", "r=r1;v=7"));
        q.add(query("nth", "sqwrl:nth", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:nth(1)", true, "r,v", "r=r1;v=7"));
        q.add(query("firstN", "sqwrl:firstN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:firstN(1)", true, "r,v", "r=r1;v=7"));
        q.add(query("lastN", "sqwrl:lastN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:lastN(1)", true, "r,v", "r=r2;v=12"));
        q.add(query("nthSlice", "sqwrl:nthSlice", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:nthSlice(1, 1)", true, "r,v", "r=r1;v=7"));
        q.add(query("nthLastSlice", "sqwrl:nthLastSlice", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:nthLastSlice(1, 1)", true, "r,v", "r=r2;v=12"));
        q.add(query("notNth", "sqwrl:notNth", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:notNth(1)", true, "r,v", "r=r2;v=12"));
        q.add(query("notFirstN", "sqwrl:notFirstN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:notFirstN(1)", true, "r,v", "r=r2;v=12"));
        q.add(query("notLastN", "sqwrl:notLastN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:notLastN(1)", true, "r,v", "r=r1;v=7"));
        q.add(query("greatestN", "sqwrl:greatestN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:greatestN(1)", true, "r,v", "r=r2;v=12"));
        q.add(query("leastN", "sqwrl:leastN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:leastN(1)", true, "r,v", "r=r1;v=7"));
        q.add(queryFails("nthLast_rejected", "sqwrl:nthLast", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:nthLast(1)", "unknown slicing operator"));
        q.add(queryFails("nthGreatest_rejected", "sqwrl:nthGreatest", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:nthGreatest(1)", "unknown slicing operator"));
        q.add(queryFails("notNthLast_rejected", "sqwrl:notNthLast", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:notNthLast(1)", "unknown slicing operator"));
        q.add(queryFails("slicing_without_order_fails", "sqwrl:greatestN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:greatestN(1)", "needs a sort order"));
        q.add(queryFails("slicing_needs_a_number", "sqwrl:firstN", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:firstN(?v, 1)", "takes only a whole number"));
        q.add(queryFails("first_after_arrow_fails", "sqwrl:first", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:first(1)", "belongs in the condition part"));
        q.add(queryFails("least_after_arrow_fails", "sqwrl:least", "Item(?r) ^ intVal(?r, ?v) -> sqwrl:select(?r, ?v) ^ sqwrl:orderBy(?v) ^ sqwrl:least(1)", "belongs in the condition part"));
        q.add(query("makeSet_size", "sqwrl:makeSet", "Item(?r) ^ flag(?r, ?f) ^ sqwrl:makeSet(?s, ?f) ^ sqwrl:size(?n, ?s) -> sqwrl:select(?n)", false, "n", "n=2"));
        q.add(query("makeBag_size", "sqwrl:makeBag", "Item(?r) ^ flag(?r, ?f) ^ sqwrl:makeBag(?s, ?f) ^ sqwrl:size(?n, ?s) -> sqwrl:select(?n)", false, "n", "n=2"));
        q.add(query("groupBy_text", "sqwrl:groupBy", "Item(?r) ^ strText(?r, ?f) ^ intVal(?r, ?v) ^ sqwrl:makeBag(?s, ?v) ^ sqwrl:groupBy(?s, ?f) ^ sqwrl:size(?n, ?s) -> sqwrl:select(?f, ?n)", false, "f,n", "f=Alpha Beta;n=1", "f=gamma;n=1"));
        q.add(queryFails("groupBy_boolean_fails", "sqwrl:groupBy", "Item(?r) ^ flag(?r, ?f) ^ intVal(?r, ?v) ^ sqwrl:makeBag(?s, ?v) ^ sqwrl:groupBy(?s, ?f) ^ sqwrl:size(?n, ?s) -> sqwrl:select(?f, ?n)", "sqwrl:makeBag could not run"));
        q.add(query("isEmpty", "sqwrl:isEmpty", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?s, ?v) ^ sqwrl:isEmpty(?s) -> sqwrl:select(?r)", false, "r"));
        q.add(query("notEmpty", "sqwrl:notEmpty", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?s, ?v) ^ sqwrl:notEmpty(?s) -> sqwrl:select(?r)", false, "r", "r=r1", "r=r2"));
        q.add(query("element", "sqwrl:element", "Item(?r) ^ Item(?q) ^ intVal(?q, ?w) ^ sqwrl:makeSet(?s, ?w) ^ sqwrl:groupBy(?s, ?r) ^ sqwrl:element(?e, ?s) -> sqwrl:select(?r, ?e)", false, "r,e", "r=r1;e=7", "r=r1;e=12", "r=r2;e=7", "r=r2;e=12"));
        q.add(query("first_of_set", "sqwrl:first", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?s, ?v) ^ sqwrl:first(?e, ?s) -> sqwrl:select(?e)", false, "e", "e=7"));
        q.add(query("last_of_set", "sqwrl:last", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?s, ?v) ^ sqwrl:last(?e, ?s) -> sqwrl:select(?e)", false, "e", "e=12"));
        q.add(query("nth_of_set", "sqwrl:nth", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?s, ?v) ^ sqwrl:nth(?e, ?s, 1) -> sqwrl:select(?e)", false, "e", "e=7"));
        q.add(query("greatest_of_set", "sqwrl:greatest", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?s, ?v) ^ sqwrl:greatest(?e, ?s) -> sqwrl:select(?e)", false, "e", "e=12"));
        q.add(query("least_of_set", "sqwrl:least", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?s, ?v) ^ sqwrl:least(?e, ?s) -> sqwrl:select(?e)", false, "e", "e=7"));
        q.add(query("union", "sqwrl:union", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?a, ?v) ^ Item(?q) ^ decVal(?q, ?w) ^ sqwrl:makeSet(?b, ?w) ^ sqwrl:union(?u, ?a, ?b) ^ sqwrl:size(?n, ?u) -> sqwrl:select(?n)", false, "n", "n=4"));
        q.add(query("intersection", "sqwrl:intersection", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?a, ?v) ^ sqwrl:makeSet(?b, ?v) ^ sqwrl:intersection(?u, ?a, ?b) ^ sqwrl:size(?n, ?u) -> sqwrl:select(?n)", false, "n", "n=2"));
        q.add(query("difference", "sqwrl:difference", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?a, ?v) ^ sqwrl:makeSet(?b, ?v) ^ sqwrl:difference(?u, ?a, ?b) ^ sqwrl:size(?n, ?u) -> sqwrl:select(?n)", false, "n", "n=0"));
        q.add(query("append", "sqwrl:append", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeBag(?a, ?v) ^ sqwrl:makeBag(?b, ?v) ^ sqwrl:append(?u, ?a, ?b) ^ sqwrl:size(?n, ?u) -> sqwrl:select(?n)", false, "n", "n=4"));
        q.add(query("equal", "sqwrl:equal", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?a, ?v) ^ sqwrl:makeSet(?b, ?v) ^ sqwrl:equal(?a, ?b) -> sqwrl:select(?r)", false, "r", "r=r1", "r=r2"));
        q.add(query("notEqual", "sqwrl:notEqual", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?a, ?v) ^ sqwrl:makeSet(?b, ?v) ^ sqwrl:notEqual(?a, ?b) -> sqwrl:select(?r)", false, "r"));
        q.add(query("intersects", "sqwrl:intersects", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?a, ?v) ^ sqwrl:makeSet(?b, ?v) ^ sqwrl:intersects(?a, ?b) -> sqwrl:select(?r)", false, "r", "r=r1", "r=r2"));
        q.add(query("notIntersects", "sqwrl:notIntersects", "Item(?r) ^ intVal(?r, ?v) ^ sqwrl:makeSet(?a, ?v) ^ sqwrl:makeSet(?b, ?v) ^ sqwrl:notIntersects(?a, ?b) -> sqwrl:select(?r)", false, "r"));
        q.add(queryFails("contains_fails", "sqwrl:contains", "Item(?r) ^ intVal(?r, ?v) ^ Item(?q) ^ intVal(?q, ?w) ^ sqwrl:makeSet(?s, ?w) ^ sqwrl:groupBy(?s, ?r) ^ sqwrl:contains(?s, ?v) -> sqwrl:select(?r, ?v)", "sqwrl:contains could not run"));
        q.add(query("with_greaterThan", "swrlb:greaterThan inside sqwrl", "Item(?r) ^ intVal(?r, ?v) ^ swrlb:greaterThan(?v, 10) -> sqwrl:select(?r, ?v)", false, "r,v", "r=r2;v=12"));
        q.add(query("with_startsWith_on_plain_text", "swrlb:startsWith inside sqwrl", "Item(?r) ^ plainText(?r, ?p) ^ swrlb:startsWith(?p, \"Alp\") -> sqwrl:select(?r, ?p)", false, "r,p", "r=r1;p=Alpha Beta"));
        q.add(query("with_stringConcat_on_plain_text", "swrlb:stringConcat inside sqwrl", "Item(?r) ^ plainText(?r, ?p) ^ swrlb:stringConcat(?o, ?p, \"!\") -> sqwrl:select(?r, ?o)", false, "r,o", "r=r1;o=Alpha Beta!", "r=r2;o=gamma!"));
        q.add(query("abox_caa", "abox:caa", "abox:caa(?c, ?i) -> sqwrl:select(?c, ?i)", false, "c,i", "c=Item;i=r1", "c=Item;i=r2"));
        q.add(query("abox_opaa", "abox:opaa", "abox:opaa(?i, ?p, ?j) -> sqwrl:select(?i, ?p, ?j)", false, "i,p,j", "i=r2;p=g:partOf;j=r1"));
        q.add(queryFails("syntax_error", "sqwrl:select", "Item(?r) -> sqwrl:select(", "Query syntax error"));
        q.add(queryFails("not_a_query", "sqwrl:select", "Item(?r) -> Hit(?r)", "is not a SQWRL query"));
        q.add(queryFails("unknown_function", "sqwrl:frobnicate", "Item(?r) -> sqwrl:frobnicate(?r)", "sqwrl:frobnicate is not a function the rule engine knows"));
        return q;
    }

    private static final class Fixture {
        final HttpServer server;
        final SwrlEngineService service;
        final SwrlEngineService validator;
        final String document;
        final Map<String, ValidationResult> validated = new ConcurrentHashMap<>();
        final Map<String, ExecutionResult> tested = new ConcurrentHashMap<>();
        final Map<String, SwrlEngineService.SqwrlQueryResult> queried = new ConcurrentHashMap<>();
        final Map<String, Set<String>> executedFacts = new ConcurrentHashMap<>();
        final Map<String, String> skipped = new ConcurrentHashMap<>();
        final List<String> executeAllErrors = java.util.Collections.synchronizedList(new ArrayList<>());

        Fixture() throws Exception {
            List<Case> cases = active();
            StringBuilder declarations = new StringBuilder();
            for (Case c : cases) {
                declarations.append("<owl:Class rdf:about=\"").append(NS).append("Hit_").append(c.id()).append("\"/>\n");
                declarations.append("<owl:DatatypeProperty rdf:about=\"").append(NS).append("out_").append(c.id()).append("\"/>\n");
            }
            declarations.append("<owl:Class rdf:about=\"").append(NS).append("Other\"/>\n");
            document = read("/greenhouse_functions.rdf").replace("</rdf:RDF>", declarations + "</rdf:RDF>");
            byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            OntologyClientService real = new OntologyClientService(new RestTemplateBuilder(), "http://127.0.0.1:" + server.getAddress().getPort());
            service = newService(real);
            OntologyClientService cached = Mockito.mock(OntologyClientService.class);
            OWLOntology shared = real.fetchOntologyWithStamp("p1", null).ontology();
            Mockito.when(cached.currentStamp(Mockito.anyString(), Mockito.any())).thenReturn("s1");
            Mockito.when(cached.fetchOntology(Mockito.anyString())).thenReturn(shared);
            Mockito.when(cached.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                    .thenReturn(new OntologyClientService.FetchedOntology(shared, 1L, null));
            Mockito.when(cached.maxAxioms()).thenReturn(1000000L);
            validator = newService(cached);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> work = new ArrayList<>();
                List<Case> good = cases.stream().filter(c -> c.kind() == Kind.WORKS).toList();
                work.add(pool.submit(() -> {
                    runExecuteAll(good);
                    return null;
                }));
                List<Case> bad = distinctFailures(cases);
                for (int i = 0; i < bad.size(); i += 3) {
                    List<Case> chunk = bad.subList(i, Math.min(bad.size(), i + 3));
                    work.add(pool.submit(() -> {
                        runExecuteAll(chunk);
                        return null;
                    }));
                }
                for (Case c : cases) {
                    work.add(pool.submit(() -> {
                        validated.put(c.id(), validator.validateRule("p1", c.ruleText()));
                        if (c.kind() != Kind.REJECTED) {
                            tested.put(c.id(), service.testSingleRule("p1", c.ruleText()));
                        }
                    }));
                }
                for (Query q : activeQueries()) {
                    work.add(pool.submit(() -> queried.put(q.id(), service.executeSqwrlQuery("p1", q.text(), "q", null))));
                }
                for (Future<?> f : work) {
                    f.get();
                }
            } finally {
                pool.shutdown();
            }
        }

        private void runExecuteAll(List<Case> group) throws Exception {
            SwrlEngineService executor = new SwrlEngineService();
            OntologyClientService client = Mockito.mock(OntologyClientService.class);
            Mockito.when(client.fetchOntology(Mockito.anyString())).thenAnswer(i -> withRules(executor, group));
            Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                    .thenAnswer(i -> new OntologyClientService.FetchedOntology(withRules(executor, group), 1L, null));
            configure(executor, client);
            ExecutionResult result = executor.executeRules("p1");
            if (!result.isSuccess()) {
                executeAllErrors.add(group.stream().map(Case::id).collect(Collectors.joining(",")) + ": " + result.getErrorMessage());
                return;
            }
            for (SkippedRule rule : result.getSkippedRules()) {
                skipped.put(rule.ruleName(), rule.reason());
            }
            for (Case c : group) {
                executedFacts.put(c.id(), new TreeSet<>());
            }
            for (var entry : factsById(result.getInferredAxioms()).entrySet()) {
                executedFacts.computeIfAbsent(entry.getKey(), k -> new TreeSet<>()).addAll(entry.getValue());
            }
        }

        private OWLOntology withRules(SwrlEngineService executor, List<Case> group) throws Exception {
            OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(document));
            PlainLiteralNormalizer.apply(ontology);
            ReflectionTestUtils.invokeMethod(executor, "ensureNamespacePrefixes", "p1", ontology);
            SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
            for (Case c : group) {
                String resolved = ReflectionTestUtils.invokeMethod(executor, "resolveEntityNames", c.ruleText(), ontology);
                engine.createSWRLRule(c.id(), resolved);
            }
            return ontology;
        }

        void close() {
            server.stop(0);
        }
    }

    private static String read(String resource) throws Exception {
        try (var in = SwrlFunctionLibrariesTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void configure(SwrlEngineService service, OntologyClientService client) {
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "ruleRepository", Mockito.mock(SwrlRuleRepository.class));
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 5000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 240);
        ReflectionTestUtils.setField(service, "executionWorkers", 8);
        ReflectionTestUtils.setField(service, "executionQueue", 500);
        ReflectionTestUtils.setField(service, "perOwnerActive", 8);
        ReflectionTestUtils.setField(service, "perOwnerWaiting", 500);
        ReflectionTestUtils.setField(service, "maxWaitMs", 900000L);
        ReflectionTestUtils.setField(service, "validationWorkers", 8);
        ReflectionTestUtils.setField(service, "validationQueue", 500);
        ReflectionTestUtils.setField(service, "validationMaxWaitMs", 900000L);
    }

    private static SwrlEngineService newService(OntologyClientService client) {
        SwrlEngineService service = new SwrlEngineService();
        configure(service, client);
        return service;
    }

    private static String local(String iri) {
        if (iri == null) {
            return "";
        }
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return iri.substring(cut + 1);
    }

    private static Map<String, Set<String>> factsById(List<InferredAxiom> axioms) {
        Map<String, Set<String>> facts = new LinkedHashMap<>();
        for (InferredAxiom axiom : axioms) {
            if ("ClassAssertion".equals(axiom.getAxiomType()) && local(axiom.getObjectIri()).startsWith("Hit_")) {
                facts.computeIfAbsent(local(axiom.getObjectIri()).substring(4), k -> new TreeSet<>()).add(local(axiom.getSubjectIri()));
            } else if ("DataPropertyAssertion".equals(axiom.getAxiomType()) && local(axiom.getPredicateIri()).startsWith("out_")) {
                facts.computeIfAbsent(local(axiom.getPredicateIri()).substring(4), k -> new TreeSet<>())
                        .add(local(axiom.getSubjectIri()) + "=" + axiom.getObjectLiteral());
            }
        }
        return facts;
    }

    private static volatile Fixture fixture;

    private static Fixture fixture() throws Exception {
        if (fixture == null) {
            synchronized (SwrlFunctionLibrariesTest.class) {
                if (fixture == null) {
                    fixture = new Fixture();
                    Runtime.getRuntime().addShutdownHook(new Thread(fixture::close));
                }
            }
        }
        return fixture;
    }

    private static Stream<Case> allCases() {
        return active().stream();
    }

    private static Stream<Case> runnableCases() {
        return active().stream().filter(c -> c.kind() != Kind.REJECTED);
    }

    private static Stream<Case> workingCases() {
        return active().stream().filter(c -> c.kind() == Kind.WORKS);
    }

    private static Stream<Case> failingCases() {
        return active().stream().filter(c -> c.kind() == Kind.RUN_FAILS);
    }

    private static List<Case> distinctFailures(List<Case> cases) {
        Set<String> seen = new LinkedHashSet<>();
        return cases.stream().filter(c -> c.kind() == Kind.RUN_FAILS && seen.add(c.message())).toList();
    }

    private static Stream<Case> distinctFailingCases() {
        return distinctFailures(active()).stream();
    }

    private static Stream<Case> rejectedCases() {
        return active().stream().filter(c -> c.kind() == Kind.REJECTED);
    }

    private static Stream<Query> allQueries() {
        return activeQueries().stream();
    }

    private static void assertFacts(Case c, Set<String> actual) {
        if (c.check() != null) {
            assertTrue(c.check().test(actual), c + " gave " + actual);
        } else {
            assertEquals(c.facts(), actual, c.toString());
        }
    }

    @ParameterizedTest(name = "validate {0}")
    @MethodSource("runnableCases")
    void validateAcceptsEveryRuleThatIsWellFormed(Case c) throws Exception {
        ValidationResult result = fixture().validated.get(c.id());
        assertTrue(result.isValid(), c + ": " + result.getErrorMessage());
    }

    @ParameterizedTest(name = "validate {0}")
    @MethodSource("rejectedCases")
    void validateRejectsUnknownNamesWithAPlainMessage(Case c) throws Exception {
        ValidationResult result = fixture().validated.get(c.id());
        assertFalse(result.isValid(), c.toString());
        assertTrue(result.getErrorMessage().contains(c.message()), c + ": " + result.getErrorMessage());
        ExecutionResult test = fixture().tested.get(c.id());
        assertTrue(test == null || !test.isSuccess());
    }

    @ParameterizedTest(name = "test {0}")
    @MethodSource("workingCases")
    void testButtonGivesTheRightConclusions(Case c) throws Exception {
        ExecutionResult result = fixture().tested.get(c.id());
        assertTrue(result.isSuccess(), c + ": " + result.getErrorMessage());
        assertFacts(c, factsById(result.getInferredAxioms()).getOrDefault(c.id(), new TreeSet<>()));
    }

    @ParameterizedTest(name = "test {0}")
    @MethodSource("failingCases")
    void testButtonExplainsAFailureInPlainWords(Case c) throws Exception {
        ExecutionResult result = fixture().tested.get(c.id());
        assertFalse(result.isSuccess(), c.toString());
        assertTrue(result.getErrorMessage().contains(c.message()), c + ": " + result.getErrorMessage());
        assertFalse(result.getErrorMessage().startsWith("Built-in function error: error running Drools rule engine"), c + ": " + result.getErrorMessage());
    }

    @ParameterizedTest(name = "execute all {0}")
    @MethodSource("workingCases")
    void executeAllGivesTheSameConclusionsAsTest(Case c) throws Exception {
        Fixture f = fixture();
        assertTrue(f.executeAllErrors.isEmpty(), f.executeAllErrors.toString());
        assertFalse(f.skipped.containsKey(c.id()), c + " was skipped: " + f.skipped.get(c.id()));
        assertFacts(c, f.executedFacts.getOrDefault(c.id(), new TreeSet<>()));
    }

    @ParameterizedTest(name = "execute all {0}")
    @MethodSource("distinctFailingCases")
    void executeAllSkipsTheFailingRuleAndNamesIt(Case c) throws Exception {
        Fixture f = fixture();
        assertTrue(f.executeAllErrors.isEmpty(), f.executeAllErrors.toString());
        String reason = f.skipped.get(c.id());
        assertTrue(reason != null, c + " was not skipped");
        assertTrue(reason.startsWith("Rule " + c.id() + " could not run: "), reason);
        assertTrue(reason.contains(c.message()), c + ": " + reason);
    }

    @ParameterizedTest(name = "sqwrl {0}")
    @MethodSource("allQueries")
    void sqwrlQueriesReturnTheRightTable(Query q) throws Exception {
        SwrlEngineService.SqwrlQueryResult result = fixture().queried.get(q.id());
        if (q.message() != null) {
            assertFalse(result.isSuccess(), q.toString());
            assertTrue(result.getErrorMessage().contains(q.message()), q + ": " + result.getErrorMessage());
            return;
        }
        assertTrue(result.isSuccess(), q + ": " + result.getErrorMessage());
        assertEquals(q.columns(), result.getColumnNames(), q.toString());
        List<String> rows = result.getRows().stream()
                .map(row -> row.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";")))
                .collect(Collectors.toList());
        if (q.ordered()) {
            assertEquals(q.rows(), rows, q.toString());
        } else {
            assertEquals(new TreeSet<>(q.rows()), new TreeSet<>(rows), q.toString());
            assertEquals(q.rows().size(), rows.size(), q.toString());
        }
    }

    @Test
    void everyFunctionTheEngineRegistersIsInTheInventoryFileTheHelpListIsCheckedAgainst() throws Exception {
        Path inventory = Path.of("..", "plugins", "swrl-editor-plugin", "tests", "engine-builtins.json");
        Assumptions.assumeTrue(Files.exists(inventory));
        Map<?, ?> expected = new ObjectMapper().readValue(Files.readString(inventory), Map.class);
        Map<String, Set<String>> actual = BuiltInCatalog.registered();
        assertEquals(new TreeSet<>(actual.keySet()), new TreeSet<>(expected.keySet().stream().map(String::valueOf).toList()));
        for (var entry : actual.entrySet()) {
            assertEquals(new TreeSet<>(entry.getValue()), new TreeSet<>(((List<?>) expected.get(entry.getKey())).stream().map(String::valueOf).toList()), entry.getKey());
        }
    }

    @Test
    void theEngineRegistersEveryFunctionLibraryTheHelpDescribes() {
        assertEquals(Set.of("swrlb", "swrlm", "swrlx", "temporal", "sqwrl", "tbox", "abox", "rbox"), BuiltInCatalog.registered().keySet());
        assertTrue(BuiltInCatalog.registered().get("swrlb").containsAll(List.of("equal", "notEqual", "lessThan", "lessThanOrEqual", "greaterThan",
                "greaterThanOrEqual", "add", "subtract", "multiply", "divide", "integerDivide", "mod", "pow", "unaryPlus", "unaryMinus", "abs", "ceiling",
                "floor", "round", "roundHalfToEven", "sin", "cos", "tan", "booleanNot", "stringEqualIgnoreCase", "stringConcat", "substring", "stringLength",
                "normalizeSpace", "upperCase", "lowerCase", "translate", "contains", "containsIgnoreCase", "startsWith", "endsWith", "substringBefore",
                "substringAfter", "matches", "replace", "tokenize", "yearMonthDuration", "dayTimeDuration", "dateTime", "date", "time",
                "addYearMonthDurations", "resolveURI", "anyURI", "listConcat", "member", "length", "first", "rest", "empty", "sublist")));
    }

    @Test
    void shopEditorExportRulesUsingFunctionsOnPlainStoredDataWork() throws Exception {
        byte[] bytes = read("/shop_editor_export.rdf").getBytes(StandardCharsets.UTF_8);
        HttpServer shop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        shop.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        shop.start();
        try {
            SwrlEngineService service = newService(new OntologyClientService(new RestTemplateBuilder(), "http://127.0.0.1:" + shop.getAddress().getPort()));
            Map<String, Set<String>> rules = new LinkedHashMap<>();
            rules.put("Employee(?e) ^ ssn(?e, ?s) ^ swrlb:startsWith(?s, \"111-\") -> SeniorEmployee(?e)", Set.of("Dave"));
            rules.put("Employee(?e) ^ ssn(?e, ?s) ^ swrlb:stringLength(9, ?s) -> SeniorEmployee(?e)", Set.of("Frank"));
            rules.put("Employee(?e) ^ ssn(?e, ?s) ^ swrlb:matches(?s, \"^[0-9]{3}-[0-9]{2}-[0-9]{4}$\") -> SeniorEmployee(?e)", Set.of("Dave", "Erin"));
            rules.put("Employee(?e) ^ ssn(?e, ?s) ^ swrlb:replace(?d, ?s, \"-\", \"\") ^ swrlb:stringLength(?n, ?d) ^ swrlb:equal(?n, 9) -> SeniorEmployee(?e)", Set.of("Dave", "Erin", "Frank"));
            rules.put("Employee(?e) ^ ssn(?e, ?s) ^ swrlb:equal(?s, \"333445555\") -> SeniorEmployee(?e)", Set.of("Frank"));
            rules.put("Employee(?e) ^ ssn(?e, ?s) ^ swrlb:notEqual(?s, \"333445555\") ^ swrlb:contains(?s, \"-22-\") -> SeniorEmployee(?e)", Set.of("Dave"));
            for (var rule : rules.entrySet()) {
                ValidationResult validation = service.validateRule("p1", rule.getKey());
                assertTrue(validation.isValid(), rule.getKey() + ": " + validation.getErrorMessage());
                ExecutionResult result = service.testSingleRule("p1", rule.getKey());
                assertTrue(result.isSuccess(), rule.getKey() + ": " + result.getErrorMessage());
                Set<String> people = result.getInferredAxioms().stream()
                        .filter(a -> "ClassAssertion".equals(a.getAxiomType()) && local(a.getObjectIri()).equals("SeniorEmployee"))
                        .map(a -> local(a.getSubjectIri())).collect(Collectors.toCollection(LinkedHashSet::new));
                assertEquals(rule.getValue(), people, rule.getKey());
            }
            SwrlEngineService.SqwrlQueryResult query = service.executeSqwrlQuery("p1",
                    "Employee(?e) ^ ssn(?e, ?s) ^ swrlb:startsWith(?s, \"222\") -> sqwrl:select(?e, ?s)", "q", null);
            assertTrue(query.isSuccess(), query.getErrorMessage());
            assertEquals(List.of(Map.of("e", "Erin", "s", "222-33-4444")), query.getRows());
        } finally {
            shop.stop(0);
        }
    }
}
