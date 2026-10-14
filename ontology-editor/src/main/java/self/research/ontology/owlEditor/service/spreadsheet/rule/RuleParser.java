package self.research.ontology.owlEditor.service.spreadsheet.rule;

import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.CellReference;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Clause;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Directive;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Item;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Literal;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Name;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.NameForm;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.TransformationRule;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Value;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleLexer.Token;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleLexer.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RuleParser {

    private static final Set<String> EXPRESSION_WORDS =
        Set.of("some", "only", "and", "or", "not", "min", "max", "exactly", "value", "that", "inverse", "self");

    private final List<Token> tokens;
    private int index;

    private RuleParser(String text) {
        this.tokens = new RuleLexer(text).tokenize();
    }

    public static TransformationRule parse(String text) {
        return new RuleParser(text).rule();
    }

    private TransformationRule rule() {
        Token head = peek();
        if (head.type() == Type.EOF) {
            throw error("The rule is empty. Start it with Class:, Individual:, ObjectProperty:, DataProperty:, "
                + "AnnotationProperty: or Datatype:", head);
        }
        if (head.type() != Type.ENTITY_KEYWORD) {
            throw error("A rule must start with Class:, Individual:, ObjectProperty:, DataProperty:, "
                + "AnnotationProperty: or Datatype:", head);
        }
        index++;
        EntityKind kind = EntityKind.fromKeyword(head.text());
        Value subject = value("the " + head.text() + " name");
        if (subject instanceof Literal literal) {
            throw new RuleParseException("An entity name cannot be a \"literal\"; use a name or cell reference",
                literal.line(), literal.column());
        }
        endOfItem();

        List<Clause> clauses = new ArrayList<>();
        while (peek().type() != Type.EOF) {
            Token keyword = peek();
            if (keyword.type() == Type.ENTITY_KEYWORD) {
                throw error("Only one entity per rule; put '" + keyword.text() + ":' in a separate rule", keyword);
            }
            if (keyword.type() != Type.CLAUSE_KEYWORD) {
                throw error("Expected a section such as Types:, Facts: or Annotations: but found '"
                    + keyword.text() + "'", keyword);
            }
            index++;
            ClauseKind clauseKind = ClauseKind.fromKeyword(keyword.text());
            if (!kind.allows(clauseKind)) {
                throw error("'" + keyword.text() + ":' cannot be used in a " + head.text() + ": rule", keyword);
            }
            clauses.add(clause(kind, clauseKind, keyword));
        }
        return new TransformationRule(kind, subject, List.copyOf(clauses));
    }

    private Clause clause(EntityKind entity, ClauseKind kind, Token keyword) {
        List<Item> items = new ArrayList<>();
        do {
            if (kind.pairs()) {
                Value property = value("a property after '" + kind.keyword() + ":'");
                if (property instanceof Literal literal) {
                    throw new RuleParseException("A property name cannot be a \"literal\"", literal.line(), literal.column());
                }
                Value val = value("a value for the property");
                items.add(new Item(property, val));
            } else {
                Value val = value("a value after '" + kind.keyword() + ":'");
                if (kind == ClauseKind.CHARACTERISTICS) {
                    checkCharacteristic(entity, val);
                } else if (val instanceof Literal literal) {
                    throw new RuleParseException("'" + kind.keyword() + ":' expects a name or cell reference, not a \"literal\"",
                        literal.line(), literal.column());
                }
                items.add(new Item(null, val));
            }
            endOfItem();
        } while (accept(Type.COMMA));
        return new Clause(kind, List.copyOf(items), keyword.line(), keyword.column());
    }

    private void checkCharacteristic(EntityKind entity, Value val) {
        if (!(val instanceof Name name) || name.form() != NameForm.PLAIN
            || !entity.allowedCharacteristics().contains(name.text())) {
            throw new RuleParseException("Characteristics for " + entity.keyword() + " must be one of "
                + String.join(", ", entity.allowedCharacteristics().stream().sorted().toList()), val.line(), val.column());
        }
    }

    private void endOfItem() {
        Token t = peek();
        switch (t.type()) {
            case COMMA, CLAUSE_KEYWORD, ENTITY_KEYWORD, EOF -> {
            }
            case NAME -> {
                if (EXPRESSION_WORDS.contains(t.text().toLowerCase(Locale.ROOT))) {
                    throw error("Class expressions ('" + t.text() + "') are not supported yet; use a simple name", t);
                }
                throw error("Unexpected '" + t.text() + "'. Separate items with commas", t);
            }
            case LPAREN -> throw error("Unexpected '('. Directives must follow a cell reference directly, e.g. @B*(xsd:decimal)", t);
            default -> throw error("Unexpected '" + t.text() + "'. Separate items with commas", t);
        }
    }

    private Value value(String expected) {
        Token t = peek();
        switch (t.type()) {
            case CELL_REF -> {
                index++;
                List<Directive> directives = peek().type() == Type.LPAREN ? directives() : List.of();
                return new CellReference(t.sheet(), t.col(), t.row(), directives, t.text(), t.line(), t.column());
            }
            case NAME -> {
                index++;
                if (EXPRESSION_WORDS.contains(t.text().toLowerCase(Locale.ROOT))) {
                    throw error("Class expressions ('" + t.text() + "') are not supported yet; use a simple name", t);
                }
                NameForm form = t.text().contains(":") ? NameForm.PREFIXED : NameForm.PLAIN;
                return new Name(t.text(), form, t.line(), t.column());
            }
            case IRI -> {
                index++;
                return new Name(t.text(), NameForm.FULL_IRI, t.line(), t.column());
            }
            case QUOTED_NAME -> {
                index++;
                return new Name(t.text(), NameForm.QUOTED, t.line(), t.column());
            }
            case STRING -> {
                index++;
                return new Literal(t.text(), t.datatype(), t.language(), t.line(), t.column());
            }
            case LPAREN -> throw error("Class expressions in brackets are not supported yet; use a simple name", t);
            default -> throw error("Expected " + expected + " (a name, cell reference like @A* or \"text\")"
                + (t.type() == Type.EOF ? " but the rule ended" : " but found '" + t.text() + "'"), t);
        }
    }

    private List<Directive> directives() {
        Token open = peek();
        index++;
        List<Directive> directives = new ArrayList<>();
        while (!accept(Type.RPAREN)) {
            Token t = peek();
            if (t.type() == Type.EOF) {
                throw error("Missing ')' to close the directives", open);
            }
            if (accept(Type.COMMA)) {
                continue;
            }
            if (t.type() == Type.LANG) {
                index++;
                directives.add(new Directive("lang", List.of(t.text()), t.line(), t.column()));
                continue;
            }
            if (t.type() != Type.NAME) {
                throw error("Expected a directive such as xsd:decimal, rdfs:label or [en] but found '" + t.text() + "'", t);
            }
            index++;
            List<String> args = new ArrayList<>();
            if (accept(Type.EQUALS)) {
                args.add(argument());
            } else if (accept(Type.LPAREN)) {
                while (!accept(Type.RPAREN)) {
                    if (peek().type() == Type.EOF) {
                        throw error("Missing ')' after the arguments of " + t.text(), t);
                    }
                    if (!accept(Type.COMMA)) {
                        args.add(argument());
                    }
                }
            }
            directives.add(new Directive(t.text(), List.copyOf(args), t.line(), t.column()));
        }
        return List.copyOf(directives);
    }

    private String argument() {
        Token t = peek();
        if (t.type() == Type.STRING || t.type() == Type.NAME || t.type() == Type.IRI || t.type() == Type.QUOTED_NAME) {
            index++;
            return t.text();
        }
        throw error("Expected a value such as \"text\"", t);
    }

    private Token peek() {
        return tokens.get(index);
    }

    private boolean accept(Type type) {
        if (peek().type() == type) {
            index++;
            return true;
        }
        return false;
    }

    private static RuleParseException error(String message, Token at) {
        return new RuleParseException(message, at.line(), at.column());
    }
}
