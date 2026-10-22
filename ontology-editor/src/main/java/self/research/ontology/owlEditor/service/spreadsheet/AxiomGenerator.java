package self.research.ontology.owlEditor.service.spreadsheet;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.spreadsheet.GenerationResult.Axiom;
import self.research.ontology.owlEditor.service.spreadsheet.GenerationResult.Issue;
import self.research.ontology.owlEditor.service.spreadsheet.GenerationResult.Severity;
import self.research.ontology.owlEditor.service.spreadsheet.OntologyLookup.KnownEntity;
import self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind;
import self.research.ontology.owlEditor.service.spreadsheet.rule.EntityKind;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.CellReference;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Clause;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Directive;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Item;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Literal;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Name;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.NameForm;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.TransformationRule;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Value;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleParseException;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleParser;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AxiomGenerator {

    public static final int MAX_AXIOMS = 250_000;

    static final String OWL = "http://www.w3.org/2002/07/owl#";
    static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    static final String OWL_THING = OWL + "Thing";
    static final String RDFS_LABEL = RDFS + "label";

    private static final Map<String, String> STANDARD_PREFIXES = Map.of(
        "owl", OWL, "rdf", RDF, "rdfs", RDFS, "xsd", XSD,
        "skos", "http://www.w3.org/2004/02/skos/core#",
        "dc", "http://purl.org/dc/elements/1.1/",
        "dcterms", "http://purl.org/dc/terms/");

    private static final Set<String> BUILT_IN_ANNOTATION_NAMESPACES = Set.of(
        "http://www.w3.org/2004/02/skos/core#", "http://purl.org/dc/elements/1.1/", "http://purl.org/dc/terms/");
    private static final Set<String> BUILT_IN_ANNOTATION_PROPERTIES = Set.of(
        RDFS + "label", RDFS + "comment", RDFS + "seeAlso", RDFS + "isDefinedBy",
        OWL + "versionInfo", OWL + "deprecated", OWL + "priorVersion", OWL + "backwardCompatibleWith",
        OWL + "incompatibleWith");

    private static final Map<String, String> CHARACTERISTIC_IRIS = Map.of(
        "Functional", OWL + "FunctionalProperty",
        "InverseFunctional", OWL + "InverseFunctionalProperty",
        "Transitive", OWL + "TransitiveProperty",
        "Symmetric", OWL + "SymmetricProperty",
        "Asymmetric", OWL + "AsymmetricProperty",
        "Reflexive", OWL + "ReflexiveProperty",
        "Irreflexive", OWL + "IrreflexiveProperty");

    private static final Set<String> SUPPORTED_DIRECTIVES = Set.of(
        "rdfs:label", "lang", "xml:lang",
        "mm:camelCaseEncode", "mm:snakeCaseEncode", "mm:uuidEncode", "mm:hashEncode",
        "mm:prefix", "mm:namespace",
        "mm:SkipIfEmptyLocation", "mm:ErrorIfEmptyLocation", "mm:WarningIfEmptyLocation",
        "mm:prepend", "mm:append", "mm:toUpperCase", "mm:toLowerCase", "mm:trim", "mm:replace",
        "rdfs:Literal", "rdf:PlainLiteral", "rdf:langString");

    private static final Pattern ABSOLUTE_IRI = Pattern.compile("^(https?|urn|file):\\S+$");
    private static final Pattern IRI_SCHEME = Pattern.compile("^(https?://|urn:|file:)");
    private static final Pattern PREFIXED_NAME = Pattern.compile("^([A-Za-z][\\w.-]*)?:([^\\s:]*)$");
    private static final Pattern UNSAFE_IRI_CHARS = Pattern.compile("[\\s<>\"{}|^`\\\\]");
    private static final Pattern LANGUAGE_TAG = Pattern.compile("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$");

    static boolean isSafeIri(String iri) {
        return iri != null && !iri.isEmpty() && !UNSAFE_IRI_CHARS.matcher(iri).find();
    }

    private final SpreadsheetWorkbook workbook;
    private final OntologyLookup lookup;
    private final Map<String, String> prefixes = new HashMap<>();
    private final String defaultNamespace;

    private final Set<MutationOp> ops = new LinkedHashSet<>();
    private final Map<String, Axiom> axioms = new LinkedHashMap<>();
    private final List<Issue> issues = new ArrayList<>();
    private final Set<String> issueKeys = new HashSet<>();

    private final Map<String, Set<EntityKind>> created = new LinkedHashMap<>();
    private final Map<EntityKind, Map<String, String>> createdByLabel = new EnumMap<>(EntityKind.class);
    private final Map<String, String> subjectIris = new HashMap<>();
    private final Set<String> declaredSubjects = new HashSet<>();
    private boolean tooLarge;
    private int rowsProcessed;

    private AxiomGenerator(SpreadsheetWorkbook workbook, OntologyLookup lookup) {
        this.workbook = workbook;
        this.lookup = lookup;
        prefixes.putAll(STANDARD_PREFIXES);
        if (lookup.prefixes() != null) {
            lookup.prefixes().forEach((p, ns) -> {
                if (p != null && ns != null && !ns.isBlank()) {
                    prefixes.put(p.endsWith(":") ? p.substring(0, p.length() - 1) : p, ns);
                }
            });
        }
        String ns = lookup.defaultNamespace();
        if (ns == null || ns.isBlank() || !isSafeIri(ns)) {
            ns = prefixes.getOrDefault("", "http://example.org/ontology#");
        }
        this.defaultNamespace = ns.endsWith("#") || ns.endsWith("/") ? ns : ns + "#";
    }

    public static GenerationResult generate(SpreadsheetWorkbook workbook, List<RuleSpec> specs, OntologyLookup lookup) {
        return new AxiomGenerator(workbook, lookup).run(specs);
    }

    private record Prepared(int number, RuleSpec spec, TransformationRule rule, SpreadsheetWorkbook.Sheet sheet,
                            RuleSpec.Range range) {
    }

    private record Cursor(Prepared rule, int row, int column) {
        String key() {
            return rule.number() + ":" + row + ":" + column;
        }
    }

    private GenerationResult run(List<RuleSpec> specs) {
        List<Prepared> prepared = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            RuleSpec spec = specs.get(i);
            if (spec != null && spec.isActive()) {
                Prepared p = prepare(i + 1, spec);
                if (p != null) {
                    prepared.add(p);
                }
            }
        }
        if (specs.stream().noneMatch(s -> s != null && s.isActive())) {
            issues.add(new Issue(Severity.ERROR, 0, null, null, null, "There are no active rules to run.", null, null));
        }

        for (Prepared p : prepared) {
            forEachCell(p, this::declareSubject);
        }
        for (Prepared p : prepared) {
            forEachCell(p, this::generateForCell);
        }
        return new GenerationResult(List.copyOf(axioms.values()), List.copyOf(ops), List.copyOf(issues), rowsProcessed);
    }

    private Prepared prepare(int number, RuleSpec spec) {
        TransformationRule rule;
        try {
            rule = RuleParser.parse(spec.rule());
        } catch (RuleParseException e) {
            issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null, e.getMessage(), e.line(), e.column()));
            return null;
        }
        SpreadsheetWorkbook.Sheet sheet = workbook.sheet(spec.sheetName());
        if (sheet == null) {
            ruleError(number, spec.sheetName(), "Sheet '" + spec.sheetName() + "' is not in this workbook");
            return null;
        }
        RuleSpec.Range range;
        try {
            range = spec.resolve(sheet);
        } catch (IllegalArgumentException e) {
            ruleError(number, spec.sheetName(), e.getMessage());
            return null;
        }
        boolean ok = checkDirectives(number, spec, rule.subject());
        for (Clause clause : rule.clauses()) {
            for (Item item : clause.items()) {
                ok &= checkDirectives(number, spec, item.property());
                ok &= checkDirectives(number, spec, item.value());
            }
        }
        if (!ok) {
            return null;
        }
        for (Value v : allCellRefs(rule)) {
            CellReference ref = (CellReference) v;
            if (ref.sheet() != null && workbook.sheet(ref.sheet()) == null) {
                issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null,
                    "Reference " + ref.source() + " points to sheet '" + ref.sheet() + "', which is not in this workbook",
                    ref.line(), ref.column()));
                return null;
            }
        }
        if (range.isEmpty()) {
            issues.add(new Issue(Severity.WARNING, number, spec.sheetName(), null, null,
                "The range has no rows in sheet '" + sheet.name() + "' (it has " + sheet.rowCount() + " rows)", null, null));
        }
        return new Prepared(number, spec, rule, sheet, range);
    }

    private static List<Value> allCellRefs(TransformationRule rule) {
        List<Value> refs = new ArrayList<>();
        if (rule.subject() instanceof CellReference) {
            refs.add(rule.subject());
        }
        for (Clause clause : rule.clauses()) {
            for (Item item : clause.items()) {
                if (item.property() instanceof CellReference) {
                    refs.add(item.property());
                }
                if (item.value() instanceof CellReference) {
                    refs.add(item.value());
                }
            }
        }
        return refs;
    }

    private boolean checkDirectives(int number, RuleSpec spec, Value value) {
        if (!(value instanceof CellReference ref)) {
            return true;
        }
        for (Directive d : ref.directives()) {
            String name = d.name();
            boolean supported = SUPPORTED_DIRECTIVES.contains(name)
                || (name.startsWith("xsd:") && OWL2Datatype.isBuiltIn(IRI.create(XSD + name.substring(4))));
            if (!supported) {
                issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null,
                    name.startsWith("xsd:") ? "Unknown datatype " + name : "Directive " + name + " is not supported yet",
                    d.line(), d.column()));
                return false;
            }
            if (("mm:prefix".equals(name) || "mm:namespace".equals(name) || "mm:prepend".equals(name)
                || "mm:append".equals(name) || "xml:lang".equals(name)) && d.argument() == null) {
                issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null,
                    name + " needs a value, e.g. " + name + "=\"…\"", d.line(), d.column()));
                return false;
            }
            if ("mm:replace".equals(name) && d.arguments().size() != 2) {
                issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null,
                    "mm:replace needs two values: mm:replace(\"from\", \"to\")", d.line(), d.column()));
                return false;
            }
            if ("mm:namespace".equals(name) && (!isSafeIri(d.argument()) || !ABSOLUTE_IRI.matcher(d.argument()).matches())) {
                issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null,
                    "mm:namespace must be an absolute IRI such as \"http://example.org/onto#\"", d.line(), d.column()));
                return false;
            }
            if (("lang".equals(name) || "xml:lang".equals(name)) && !LANGUAGE_TAG.matcher(d.argument()).matches()) {
                issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null,
                    "'" + d.argument() + "' is not a language tag; use e.g. [en] or [en-GB]", d.line(), d.column()));
                return false;
            }
            if ("mm:prefix".equals(name) && !prefixes.containsKey(d.argument())) {
                issues.add(new Issue(Severity.ERROR, number, spec.sheetName(), null, null,
                    "Prefix '" + d.argument() + "' is not defined in this ontology", d.line(), d.column()));
                return false;
            }
        }
        return true;
    }

    private interface CellAction {
        void apply(Cursor cursor);
    }

    private void forEachCell(Prepared p, CellAction action) {
        RuleSpec.Range r = p.range();
        for (int row = r.startRow(); row <= r.endRow() && !tooLarge; row++) {
            for (int col = r.startColumn(); col <= r.endColumn() && !tooLarge; col++) {
                action.apply(new Cursor(p, row, col));
            }
        }
    }

    private void declareSubject(Cursor c) {
        EntityKind kind = c.rule().rule().kind();
        Value subject = c.rule().rule().subject();
        String text = textOf(subject, c);
        if (text == null) {
            return;
        }
        boolean byLabel = hasDirective(subject, "rdfs:label");
        String iri = null;
        if (byLabel) {
            iri = findByLabel(text, kind);
        }
        if (iri == null) {
            iri = explicitIri(text, subject, c);
            if (iri == null && issueRaised) {
                issueRaised = false;
                return;
            }
        }
        if (iri == null) {
            String local = encode(text, subject, c);
            if (local.isEmpty()) {
                cellIssue(Severity.ERROR, c, subject, "'" + text + "' cannot be turned into an entity name");
                return;
            }
            iri = namespaceFor(subject) + local;
            if (!existsAs(iri, kind) && !byLabel && !hasNamespaceDirective(subject)) {
                String existing = findByShortName(local, kind, subject, c);
                if (ambiguous) {
                    ambiguous = false;
                    return;
                }
                if (existing != null) {
                    iri = existing;
                }
            }
        }
        if (!existsAs(iri, kind)) {
            created.computeIfAbsent(iri, k -> new HashSet<>()).add(kind);
            declaredSubjects.add(iri);
            if (byLabel) {
                createdByLabel.computeIfAbsent(kind, k -> new HashMap<>()).putIfAbsent(text, iri);
            }
        }
        subjectIris.put(c.key(), iri);
    }

    private void generateForCell(Cursor c) {
        String subjectIri = subjectIris.get(c.key());
        if (subjectIri == null) {
            return;
        }
        rowsProcessed++;
        TransformationRule rule = c.rule().rule();
        EntityKind kind = rule.kind();
        Value subject = rule.subject();
        String label = hasDirective(subject, "rdfs:label") ? textOf(subject, c) : null;

        List<Resolved> parents = new ArrayList<>();
        for (Clause clause : rule.clauses()) {
            ClauseKind ck = clause.kind();
            if ((kind == EntityKind.CLASS && ck == ClauseKind.SUB_CLASS_OF)
                || (kind == EntityKind.INDIVIDUAL && ck == ClauseKind.TYPES)
                || (isProperty(kind) && ck == ClauseKind.SUB_PROPERTY_OF)) {
                for (Item item : clause.items()) {
                    Resolved r = resolveReference(item.value(), targetKindFor(kind, ck), c);
                    if (r != null) {
                        parents.add(r);
                    }
                }
            }
        }

        boolean declare = declaredSubjects.remove(subjectIri);
        String first = parents.isEmpty() ? null : parents.get(0).iri();
        if (declare) {
            declare(subjectIri, kind, label, first, c);
        } else if (label != null) {
            annotation(subjectIri, RDFS_LABEL, label, null, null, c);
        }
        for (int i = 0; i < parents.size(); i++) {
            if (i == 0 && declare) {
                continue;
            }
            String target = parents.get(i).iri();
            switch (kind) {
                case CLASS -> emit(op("addSubClassOf", subjectIri, null, null, null, target, null, null, null),
                    "SubClassOf", show(subjectIri) + " SubClassOf " + show(target), c);
                case INDIVIDUAL -> emit(op("addClassAssertion", subjectIri, null, null, null, null, target, null, null),
                    "ClassAssertion", show(subjectIri) + " Type " + show(target), c);
                default -> emit(op("addSubPropertyOf", subjectIri, null, null, null, target, null, null, null),
                    "SubPropertyOf", show(subjectIri) + " SubPropertyOf " + show(target), c);
            }
        }

        for (Clause clause : rule.clauses()) {
            switch (clause.kind()) {
                case TYPES, SUB_CLASS_OF, SUB_PROPERTY_OF -> {
                }
                case FACTS -> clause.items().forEach(item -> fact(subjectIri, item, c));
                case ANNOTATIONS -> clause.items().forEach(item -> annotationItem(subjectIri, item, c));
                case EQUIVALENT_TO, DISJOINT_WITH -> clause.items().forEach(item -> {
                    Resolved t = resolveReference(item.value(), EntityKind.CLASS, c);
                    if (t != null) {
                        boolean eq = clause.kind() == ClauseKind.EQUIVALENT_TO;
                        emit(op(eq ? "addEquivalentClass" : "addDisjointWith", subjectIri, null, null, null, t.iri(), null, null, null),
                            eq ? "EquivalentClasses" : "DisjointClasses",
                            show(subjectIri) + (eq ? " EquivalentTo " : " DisjointWith ") + show(t.iri()), c);
                    }
                });
                case SAME_AS, DIFFERENT_FROM -> clause.items().forEach(item -> {
                    Resolved t = resolveReference(item.value(), EntityKind.INDIVIDUAL, c);
                    if (t != null) {
                        boolean same = clause.kind() == ClauseKind.SAME_AS;
                        emit(op(same ? "addSameIndividual" : "addDifferentIndividual", subjectIri, null, null, null, t.iri(), null, null, null),
                            same ? "SameIndividual" : "DifferentIndividuals",
                            show(subjectIri) + (same ? " SameAs " : " DifferentFrom ") + show(t.iri()), c);
                    }
                });
                case DOMAIN -> clause.items().forEach(item -> {
                    Resolved t = resolveReference(item.value(), EntityKind.CLASS, c);
                    if (t != null) {
                        emit(op("addPropertyDomain", subjectIri, null, null, null, t.iri(), null, null, null),
                            "PropertyDomain", show(subjectIri) + " Domain " + show(t.iri()), c);
                    }
                });
                case RANGE -> clause.items().forEach(item -> range(subjectIri, kind, item, c));
                case INVERSE_OF -> clause.items().forEach(item -> {
                    Resolved t = resolveProperty(item.value(), c, Set.of(EntityKind.OBJECT_PROPERTY));
                    if (t != null) {
                        emit(op("addInverseProperty", subjectIri, null, null, null, t.iri(), null, null, null),
                            "InverseObjectProperties", show(subjectIri) + " InverseOf " + show(t.iri()), c);
                    }
                });
                case CHARACTERISTICS -> clause.items().forEach(item -> {
                    String name = ((Name) item.value()).text();
                    emit(op("addCharacteristic", subjectIri, null, null, null, CHARACTERISTIC_IRIS.get(name), null, null, null),
                        "PropertyCharacteristic", show(subjectIri) + " Characteristics " + name, c);
                });
            }
        }
    }

    private static EntityKind targetKindFor(EntityKind subject, ClauseKind clause) {
        if (clause == ClauseKind.SUB_PROPERTY_OF) {
            return subject;
        }
        return EntityKind.CLASS;
    }

    private static boolean isProperty(EntityKind kind) {
        return kind == EntityKind.OBJECT_PROPERTY || kind == EntityKind.DATA_PROPERTY || kind == EntityKind.ANNOTATION_PROPERTY;
    }

    private void declare(String iri, EntityKind kind, String label, String parent, Cursor c) {
        String s = show(iri);
        switch (kind) {
            case CLASS -> {
                String p = parent != null ? parent : OWL_THING;
                emit(create("createClass", iri, label, p, null), "Declaration", "Class: " + s, c);
                if (parent != null) {
                    axiom("SubClassOf", s + " SubClassOf " + show(parent), c);
                }
            }
            case INDIVIDUAL -> {
                String type = parent != null ? parent : OWL_THING;
                emit(create("createIndividual", iri, label, null, type), "Declaration", "Individual: " + s, c);
                if (parent != null) {
                    axiom("ClassAssertion", s + " Type " + show(parent), c);
                }
            }
            case OBJECT_PROPERTY, DATA_PROPERTY, ANNOTATION_PROPERTY -> {
                String opType = switch (kind) {
                    case OBJECT_PROPERTY -> "createObjectProperty";
                    case DATA_PROPERTY -> "createDataProperty";
                    default -> "createAnnotationProperty";
                };
                emit(create(opType, iri, label, parent, null), "Declaration", kind.keyword() + ": " + s, c);
                if (parent != null) {
                    axiom("SubPropertyOf", s + " SubPropertyOf " + show(parent), c);
                }
            }
            case DATATYPE -> emit(create("createDatatype", iri, label, null, null),
                "Declaration", "Datatype: " + s, c);
        }
        if (label != null) {
            axiom("AnnotationAssertion", s + " rdfs:label " + showLiteral(label, null, null), c);
        }
    }

    private void fact(String subjectIri, Item item, Cursor c) {
        Resolved property = resolveProperty(item.property(), c,
            Set.of(EntityKind.DATA_PROPERTY, EntityKind.OBJECT_PROPERTY, EntityKind.ANNOTATION_PROPERTY));
        if (property == null) {
            return;
        }
        switch (property.kind()) {
            case OBJECT_PROPERTY -> {
                Resolved target = resolveReference(item.value(), EntityKind.INDIVIDUAL, c);
                if (target != null) {
                    emit(op("addObjectPropertyAssertion", subjectIri, null, property.iri(), null, target.iri(), null, null, null),
                        "ObjectPropertyAssertion", show(subjectIri) + " " + show(property.iri()) + " " + show(target.iri()), c);
                }
            }
            case DATA_PROPERTY -> {
                LiteralValue lit = literalOf(item.value(), c);
                if (lit == null) {
                    return;
                }
                String datatype = lit.datatype();
                String range = rangeOf(property.iri());
                if (datatype == null && lit.language() == null && range != null && isConcreteDatatype(range)) {
                    datatype = range;
                } else if (datatype != null && range != null && isConcreteDatatype(range) && !datatype.equals(range)) {
                    ruleIssue(Severity.WARNING, c, item.value(), show(property.iri()) + " has range " + show(range)
                        + " but the rule types its values as " + show(datatype));
                }
                if (!validLexical(lit.text(), datatype, c, item.value())) {
                    return;
                }
                String text = canonical(lit.text(), datatype);
                emit(op("addDataPropertyAssertion", subjectIri, null, property.iri(), text, null, null, lit.language(), datatype),
                    "DataPropertyAssertion",
                    show(subjectIri) + " " + show(property.iri()) + " " + showLiteral(text, datatype, lit.language()), c);
            }
            default -> annotation(subjectIri, property.iri(), item.value(), c);
        }
    }

    private void annotationItem(String subjectIri, Item item, Cursor c) {
        Resolved property = resolveProperty(item.property(), c, Set.of(EntityKind.ANNOTATION_PROPERTY));
        if (property != null) {
            annotation(subjectIri, property.iri(), item.value(), c);
        }
    }

    private void annotation(String subjectIri, String propertyIri, Value value, Cursor c) {
        LiteralValue lit = literalOf(value, c);
        if (lit != null && validLexical(lit.text(), lit.datatype(), c, value)) {
            annotation(subjectIri, propertyIri, canonical(lit.text(), lit.datatype()), lit.language(), lit.datatype(), c);
        }
    }

    private void annotation(String subjectIri, String propertyIri, String text, String language, String datatype, Cursor c) {
        emit(op("addAnnotation", subjectIri, null, propertyIri, text, null, null, language, datatype),
            "AnnotationAssertion", show(subjectIri) + " " + show(propertyIri) + " " + showLiteral(text, datatype, language), c);
    }

    private void range(String subjectIri, EntityKind kind, Item item, Cursor c) {
        String target;
        if (kind == EntityKind.DATA_PROPERTY) {
            String text = textOf(item.value(), c);
            if (text == null) {
                return;
            }
            target = explicitIri(text, item.value(), c);
            if (target == null) {
                if (!issueRaised) {
                    cellIssue(Severity.ERROR, c, item.value(), "'" + text + "' is not a datatype; use e.g. xsd:decimal");
                }
                issueRaised = false;
                return;
            }
            if (!isDatatype(target)) {
                cellIssue(Severity.ERROR, c, item.value(), show(target) + " is not a datatype");
                return;
            }
        } else if (kind == EntityKind.OBJECT_PROPERTY) {
            Resolved t = resolveReference(item.value(), EntityKind.CLASS, c);
            if (t == null) {
                return;
            }
            target = t.iri();
        } else {
            String text = textOf(item.value(), c);
            if (text == null) {
                return;
            }
            target = explicitIri(text, item.value(), c);
            if (target == null) {
                issueRaised = false;
                target = namespaceFor(item.value()) + encode(text, item.value(), c);
            }
        }
        emit(op("addPropertyRange", subjectIri, null, null, null, target, null, null, null),
            "PropertyRange", show(subjectIri) + " Range " + show(target), c);
    }

    private record Resolved(String iri, EntityKind kind) {
    }

    private boolean issueRaised;

    private Resolved resolveReference(Value value, EntityKind expected, Cursor c) {
        String text = textOf(value, c);
        if (text == null) {
            return null;
        }
        if (value instanceof Literal) {
            cellIssue(Severity.ERROR, c, value, "Expected a name, not a \"literal\"");
            return null;
        }
        String iri = explicitIri(text, value, c);
        if (iri == null && issueRaised) {
            issueRaised = false;
            return null;
        }
        boolean byLabel = hasDirective(value, "rdfs:label");
        if (iri == null) {
            iri = byLabel ? findByLabel(text, expected) : findByName(text, value, expected, c);
            if (ambiguous) {
                ambiguous = false;
                return null;
            }
        }
        if (iri == null && !byLabel && !(value instanceof CellReference) && expected != EntityKind.INDIVIDUAL) {
            iri = findByLabel(text, expected);
        }
        if (iri == null) {
            String local = encode(text, value, c);
            if (local.isEmpty()) {
                cellIssue(Severity.ERROR, c, value, "'" + text + "' cannot be turned into an entity name");
                return null;
            }
            iri = namespaceFor(value) + local;
        }
        boolean existsAtAll = exists(iri);
        if (!existsAtAll && expected != EntityKind.CLASS && expected != EntityKind.INDIVIDUAL) {
            cellIssue(Severity.ERROR, c, value, show(iri) + " is not in the ontology");
            return null;
        }
        if (!existsAs(iri, expected)) {
            created.computeIfAbsent(iri, k -> new HashSet<>()).add(expected);
            if (byLabel) {
                createdByLabel.computeIfAbsent(expected, k -> new HashMap<>()).putIfAbsent(text, iri);
            }
            String label = byLabel ? text : null;
            declare(iri, expected, label, null, c);
            if (!existsAtAll) {
                ruleIssue(Severity.WARNING, c, value, (expected == EntityKind.CLASS ? "Class " : "Individual ")
                    + show(iri) + " is not in the ontology and will be created");
            }
        }
        return new Resolved(iri, expected);
    }

    private Resolved resolveProperty(Value value, Cursor c, Set<EntityKind> allowed) {
        String text = textOf(value, c);
        if (text == null) {
            return null;
        }
        String iri = explicitIri(text, value, c);
        if (iri == null && issueRaised) {
            issueRaised = false;
            return null;
        }
        if (iri == null) {
            for (EntityKind k : allowed) {
                iri = findByName(text, value, k, c);
                if (ambiguous) {
                    ambiguous = false;
                    return null;
                }
                if (iri == null) {
                    iri = findByLabel(text, k);
                }
                if (iri != null) {
                    break;
                }
            }
        }
        if (iri == null) {
            ruleIssue(Severity.ERROR, c, value, "Property '" + text + "' is not in the ontology. Create it first, "
                + "or add a DataProperty:/ObjectProperty: rule for it");
            return null;
        }
        EntityKind kind = propertyKind(iri, allowed);
        if (kind == null) {
            ruleIssue(Severity.ERROR, c, value, show(iri) + " is not "
                + (allowed.size() == 1 ? "an annotation property" : "a data, object or annotation property"));
            return null;
        }
        return new Resolved(iri, kind);
    }

    private EntityKind propertyKind(String iri, Set<EntityKind> allowed) {
        Set<EntityKind> kinds = kindsOf(iri);
        for (EntityKind k : List.of(EntityKind.DATA_PROPERTY, EntityKind.OBJECT_PROPERTY, EntityKind.ANNOTATION_PROPERTY)) {
            if (allowed.contains(k) && kinds.contains(k)) {
                return k;
            }
        }
        return null;
    }

    private String explicitIri(String text, Value value, Cursor c) {
        String iri = explicitIriUnchecked(text, value, c);
        if (iri != null && !isSafeIri(iri)) {
            cellIssue(Severity.ERROR, c, value, "'" + iri + "' is not a valid IRI (it contains spaces or one of < > \" { } | ^ ` \\)");
            issueRaised = true;
            return null;
        }
        return iri;
    }

    private String explicitIriUnchecked(String text, Value value, Cursor c) {
        if (value instanceof Name name && name.form() == NameForm.FULL_IRI) {
            return text;
        }
        if (value instanceof Name name && name.form() == NameForm.QUOTED) {
            return null;
        }
        if (ABSOLUTE_IRI.matcher(text).matches() || IRI_SCHEME.matcher(text).find()) {
            return text;
        }
        var m = PREFIXED_NAME.matcher(text);
        if (m.matches() && (value instanceof Name || prefixes.containsKey(m.group(1) == null ? "" : m.group(1)))) {
            String prefix = m.group(1) == null ? "" : m.group(1);
            String ns = prefixes.get(prefix);
            if (ns == null) {
                cellIssue(Severity.ERROR, c, value, "Prefix '" + prefix + ":' is not defined in this ontology");
                issueRaised = true;
                return null;
            }
            return ns + m.group(2);
        }
        return null;
    }

    private String findByName(String text, Value value, EntityKind kind, Cursor c) {
        String local = encode(text, value, null);
        if (local.isEmpty()) {
            return null;
        }
        String iri = namespaceFor(value) + local;
        if (kindsOf(iri).contains(kind) || (kind == EntityKind.CLASS && OWL_THING.equals(iri))) {
            return iri;
        }
        return hasNamespaceDirective(value) ? null : findByShortName(local, kind, value, c);
    }

    private boolean ambiguous;

    private String findByShortName(String local, EntityKind kind, Value value, Cursor c) {
        Set<String> matches = new LinkedHashSet<>();
        lookup.byShortName(local).stream().filter(e -> e.kinds().contains(kind)).forEach(e -> matches.add(e.iri()));
        created.forEach((iri, kinds) -> {
            if (kinds.contains(kind) && (iri.endsWith("#" + local) || iri.endsWith("/" + local))) {
                matches.add(iri);
            }
        });
        if (matches.size() == 1) {
            return matches.iterator().next();
        }
        if (matches.size() > 1) {
            ruleIssue(Severity.ERROR, c, value, "'" + local + "' matches " + matches.size() + " entities ("
                + matches.stream().map(this::show).collect(java.util.stream.Collectors.joining(", "))
                + "); write it with a prefix to choose one");
            ambiguous = true;
        }
        return null;
    }

    private static boolean hasNamespaceDirective(Value value) {
        return hasDirective(value, "mm:prefix") || hasDirective(value, "mm:namespace");
    }

    private String findByLabel(String label, EntityKind kind) {
        Map<String, String> mine = createdByLabel.get(kind);
        if (mine != null && mine.containsKey(label)) {
            return mine.get(label);
        }
        List<KnownEntity> matches = lookup.byLabel(label).stream().filter(e -> e.kinds().contains(kind)).toList();
        return matches.isEmpty() ? null : matches.get(0).iri();
    }

    private boolean exists(String iri) {
        return !kindsOf(iri).isEmpty() || OWL_THING.equals(iri) || isDatatype(iri);
    }

    private boolean existsAs(String iri, EntityKind kind) {
        return kindsOf(iri).contains(kind) || (kind == EntityKind.DATATYPE && isDatatype(iri));
    }

    private Set<EntityKind> kindsOf(String iri) {
        Set<EntityKind> kinds = new HashSet<>(created.getOrDefault(iri, Set.of()));
        lookup.byIri(iri).ifPresent(e -> kinds.addAll(e.kinds()));
        if (BUILT_IN_ANNOTATION_PROPERTIES.contains(iri)
            || BUILT_IN_ANNOTATION_NAMESPACES.stream().anyMatch(iri::startsWith)) {
            kinds.add(EntityKind.ANNOTATION_PROPERTY);
        }
        if (OWL_THING.equals(iri)) {
            kinds.add(EntityKind.CLASS);
        }
        return kinds;
    }

    private String rangeOf(String propertyIri) {
        return lookup.byIri(propertyIri).map(KnownEntity::dataRange).orElse(null);
    }

    private boolean isDatatype(String iri) {
        return OWL2Datatype.isBuiltIn(IRI.create(iri)) || kindsOf(iri).contains(EntityKind.DATATYPE);
    }

    private static boolean isConcreteDatatype(String iri) {
        if (!OWL2Datatype.isBuiltIn(IRI.create(iri))) {
            return false;
        }
        OWL2Datatype dt = OWL2Datatype.getDatatype(IRI.create(iri));
        return dt != OWL2Datatype.RDFS_LITERAL && dt != OWL2Datatype.RDF_PLAIN_LITERAL && dt != OWL2Datatype.RDF_LANG_STRING;
    }

    private record LiteralValue(String text, String datatype, String language) {
    }

    private LiteralValue literalOf(Value value, Cursor c) {
        if (value instanceof Literal l) {
            String dt = l.datatype() == null ? null : expandDatatype(l.datatype(), c, value);
            if (l.datatype() != null && dt == null) {
                return null;
            }
            return new LiteralValue(l.text(), dt, l.language());
        }
        String text = textOf(value, c);
        if (text == null) {
            return null;
        }
        String datatype = null;
        String language = null;
        if (value instanceof CellReference ref) {
            for (Directive d : ref.directives()) {
                String n = d.name();
                if (n.startsWith("xsd:")) {
                    datatype = XSD + n.substring(4);
                } else if ("rdfs:Literal".equals(n)) {
                    datatype = RDFS + "Literal";
                } else if ("rdf:PlainLiteral".equals(n) || "rdf:langString".equals(n)) {
                    datatype = null;
                } else if ("lang".equals(n) || "xml:lang".equals(n)) {
                    language = d.argument();
                }
            }
        }
        if (datatype != null && language != null) {
            ruleIssue(Severity.ERROR, c, value, "A value cannot have both a datatype and a language tag");
            return null;
        }
        return new LiteralValue(text, datatype, language);
    }

    private String expandDatatype(String datatype, Cursor c, Value at) {
        String iri;
        if (ABSOLUTE_IRI.matcher(datatype).matches()) {
            iri = datatype;
        } else {
            int colon = datatype.indexOf(':');
            String ns = colon < 0 ? null : prefixes.get(datatype.substring(0, colon));
            iri = ns == null ? null : ns + datatype.substring(colon + 1);
        }
        if (iri == null || !isSafeIri(iri)) {
            ruleIssue(Severity.ERROR, c, at, "Unknown datatype " + datatype);
            return null;
        }
        return iri;
    }

    private boolean validLexical(String text, String datatype, Cursor c, Value at) {
        if (datatype == null || !isConcreteDatatype(datatype)) {
            return true;
        }
        OWL2Datatype dt = OWL2Datatype.getDatatype(IRI.create(datatype));
        if (dt.isInLexicalSpace(text)) {
            return true;
        }
        cellIssue(Severity.ERROR, c, at, "'" + text + "' is not a valid " + show(datatype) + hint(dt));
        return false;
    }

    static String canonical(String text, String datatype) {
        if (!(XSD + "decimal").equals(datatype)) {
            return text;
        }
        BigDecimal value = new BigDecimal(text.trim()).stripTrailingZeros();
        return (value.scale() <= 0 ? value.setScale(1) : value).toPlainString();
    }

    private static String hint(OWL2Datatype dt) {
        return switch (dt) {
            case XSD_DATE_TIME, XSD_DATE_TIME_STAMP -> " (expected e.g. 2026-09-02T10:01:00)";
            case XSD_INTEGER, XSD_INT, XSD_LONG, XSD_SHORT, XSD_NON_NEGATIVE_INTEGER, XSD_POSITIVE_INTEGER -> " (expected a whole number)";
            case XSD_DECIMAL, XSD_DOUBLE, XSD_FLOAT -> " (expected a number such as 23.7)";
            case XSD_BOOLEAN -> " (expected true or false)";
            default -> "";
        };
    }

    private String textOf(Value value, Cursor c) {
        if (value instanceof Name n) {
            return n.text();
        }
        if (value instanceof Literal l) {
            return l.text();
        }
        CellReference ref = (CellReference) value;
        SpreadsheetWorkbook.Sheet sheet = ref.sheet() != null ? workbook.sheet(ref.sheet()) : c.rule().sheet();
        int col = ref.currentColumn() ? c.column() : ref.sheetColumn();
        int row = ref.currentRow() ? c.row() : ref.row();
        String text = sheet.cell(row, col);
        for (Directive d : ref.directives()) {
            text = switch (d.name()) {
                case "mm:trim" -> text.trim();
                case "mm:toUpperCase" -> text.toUpperCase(Locale.ROOT);
                case "mm:toLowerCase" -> text.toLowerCase(Locale.ROOT);
                case "mm:prepend" -> text.isEmpty() ? text : d.argument() + text;
                case "mm:append" -> text.isEmpty() ? text : text + d.argument();
                case "mm:replace" -> text.replace(d.arguments().get(0), d.arguments().get(1));
                default -> text;
            };
        }
        if (!text.isEmpty()) {
            return text;
        }
        String where = (ref.sheet() != null ? ref.sheet() + "!" : "") + SpreadsheetParserService.columnLetter(col) + row;
        boolean isSubject = value == c.rule().rule().subject();
        if (hasDirective(value, "mm:ErrorIfEmptyLocation")) {
            issue(Severity.ERROR, c, where, "Cell " + where + " is empty", value);
        } else if (hasDirective(value, "mm:SkipIfEmptyLocation")) {
        } else if (isSubject) {
            issue(Severity.WARNING, c, where, "Cell " + where + " is empty, so this row was skipped", value);
        } else if (hasDirective(value, "mm:WarningIfEmptyLocation")) {
            issue(Severity.WARNING, c, where, "Cell " + where + " is empty and was skipped", value);
        }
        return null;
    }

    private static boolean hasDirective(Value value, String name) {
        return value instanceof CellReference ref && ref.directives().stream().anyMatch(d -> d.name().equals(name));
    }

    private String namespaceFor(Value value) {
        if (value instanceof CellReference ref) {
            for (Directive d : ref.directives()) {
                if ("mm:namespace".equals(d.name())) {
                    return d.argument();
                }
                if ("mm:prefix".equals(d.name())) {
                    return prefixes.get(d.argument());
                }
            }
        }
        return defaultNamespace;
    }

    private String encode(String text, Value value, Cursor c) {
        String t = text.trim();
        if (hasDirective(value, "mm:uuidEncode")) {
            String seed = (c == null ? "" : c.rule().sheet().name() + "!" + c.row() + ":" + c.column() + ":") + t;
            return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
        }
        if (hasDirective(value, "mm:hashEncode")) {
            return sha256(t).substring(0, 32);
        }
        String[] words = t.split("[^\\p{L}\\p{N}_.-]+");
        StringBuilder sb = new StringBuilder();
        boolean camel = hasDirective(value, "mm:camelCaseEncode");
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                if (camel) {
                    sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                    continue;
                }
                sb.append('_');
            }
            sb.append(w);
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MutationOp op(String type, String iri, String label, String property, String value,
                                 String target, String classIri, String language, String datatype) {
        return new MutationOp(type, iri, label, null, property, value, target, classIri,
            null, null, null, null, language, datatype, null);
    }

    private static MutationOp create(String type, String iri, String label, String parent, String classIri) {
        return new MutationOp(type, iri, label, parent, null, null, null, classIri,
            null, null, null, null, null, null, null);
    }

    private void emit(MutationOp op, String axiomType, String text, Cursor c) {
        if (tooLarge) {
            return;
        }
        ops.add(op);
        axiom(axiomType, text, c);
    }

    private void axiom(String type, String text, Cursor c) {
        String key = type + "|" + text;
        if (axioms.containsKey(key)) {
            return;
        }
        if (axioms.size() >= MAX_AXIOMS) {
            tooLarge = true;
            issues.add(new Issue(Severity.ERROR, c.rule().number(), c.rule().sheet().name(), null, null,
                "The rules generate more than " + MAX_AXIOMS + " axioms; split the import", null, null));
            return;
        }
        axioms.put(key, new Axiom(type, text, c.rule().number(), c.rule().sheet().name(), c.row()));
    }

    private void cellIssue(Severity severity, Cursor c, Value at, String message) {
        String cell = null;
        if (at instanceof CellReference ref) {
            int col = ref.currentColumn() ? c.column() : ref.sheetColumn();
            int row = ref.currentRow() ? c.row() : ref.row();
            cell = (ref.sheet() != null ? ref.sheet() + "!" : "") + SpreadsheetParserService.columnLetter(col) + row;
        }
        issue(severity, c, cell, message, at);
    }

    private void ruleIssue(Severity severity, Cursor c, Value at, String message) {
        String key = c.rule().number() + "|" + message;
        if (issueKeys.add(key)) {
            issues.add(new Issue(severity, c.rule().number(), c.rule().sheet().name(), null, null, message,
                at == null ? null : at.line(), at == null ? null : at.column()));
        }
    }

    private void issue(Severity severity, Cursor c, String cell, String message, Value at) {
        String key = c.rule().number() + "|" + c.row() + "|" + cell + "|" + message;
        if (issueKeys.add(key)) {
            issues.add(new Issue(severity, c.rule().number(), c.rule().sheet().name(), c.row(), cell, message,
                at == null ? null : at.line(), at == null ? null : at.column()));
        }
    }

    private void ruleError(int number, String sheet, String message) {
        issues.add(new Issue(Severity.ERROR, number, sheet, null, null, message, null, null));
    }

    String show(String iri) {
        if (iri == null) {
            return "?";
        }
        if (iri.startsWith(defaultNamespace) && iri.length() > defaultNamespace.length()) {
            return iri.substring(defaultNamespace.length());
        }
        String bestPrefix = null;
        String bestNs = "";
        for (Map.Entry<String, String> e : prefixes.entrySet()) {
            if (!e.getKey().isEmpty() && iri.startsWith(e.getValue()) && e.getValue().length() > bestNs.length()) {
                bestPrefix = e.getKey();
                bestNs = e.getValue();
            }
        }
        if (bestPrefix != null && iri.length() > bestNs.length()) {
            return bestPrefix + ":" + iri.substring(bestNs.length());
        }
        return "<" + iri + ">";
    }

    private String showLiteral(String text, String datatype, String language) {
        String quoted = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (language != null) {
            return quoted + "@" + language;
        }
        if (datatype != null) {
            return quoted + "^^" + show(datatype);
        }
        return quoted;
    }
}
