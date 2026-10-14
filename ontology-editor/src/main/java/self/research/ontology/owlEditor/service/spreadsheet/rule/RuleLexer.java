package self.research.ontology.owlEditor.service.spreadsheet.rule;

import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetParserService;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RuleLexer {

    enum Type { ENTITY_KEYWORD, CLAUSE_KEYWORD, NAME, IRI, QUOTED_NAME, CELL_REF, STRING, LANG, LPAREN, RPAREN, COMMA, EQUALS, EOF }

    record Token(Type type, String text, int line, int column,
                 String sheet, Integer col, Integer row, String datatype, String language) {
        static Token of(Type type, String text, int line, int column) {
            return new Token(type, text, line, column, null, null, null, null, null);
        }
    }

    private static final Pattern CELL = Pattern.compile("([A-Za-z]+|\\*)([0-9]+|\\*)");

    private final String src;
    private int pos;
    private int line = 1;
    private int lineStart;

    RuleLexer(String src) {
        this.src = src == null ? "" : src;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (pos >= src.length()) {
                tokens.add(Token.of(Type.EOF, "", line, col()));
                return tokens;
            }
            tokens.add(next());
        }
    }

    private Token next() {
        int startLine = line;
        int startCol = col();
        char ch = src.charAt(pos);
        switch (ch) {
            case '(' -> { pos++; return Token.of(Type.LPAREN, "(", startLine, startCol); }
            case ')' -> { pos++; return Token.of(Type.RPAREN, ")", startLine, startCol); }
            case ',' -> { pos++; return Token.of(Type.COMMA, ",", startLine, startCol); }
            case '=' -> { pos++; return Token.of(Type.EQUALS, "=", startLine, startCol); }
            case '<' -> { return Token.of(Type.IRI, readDelimited('<', '>', "IRI"), startLine, startCol); }
            case '\'' -> { return Token.of(Type.QUOTED_NAME, readDelimited('\'', '\'', "quoted name"), startLine, startCol); }
            case '[' -> { return Token.of(Type.LANG, readDelimited('[', ']', "language tag").trim(), startLine, startCol); }
            case '"' -> { return readString(startLine, startCol); }
            case '@' -> { return readCellReference(startLine, startCol); }
            default -> {
                if (isNameStart(ch)) {
                    return readName(startLine, startCol);
                }
                throw new RuleParseException("Unexpected character '" + ch + "'", startLine, startCol);
            }
        }
    }

    private Token readName(int startLine, int startCol) {
        int start = pos;
        while (pos < src.length() && isNamePart(src.charAt(pos))) {
            pos++;
        }
        String text = src.substring(start, pos);
        int colon = text.indexOf(':');
        if (colon > 0) {
            String head = text.substring(0, colon);
            Type keywordType = EntityKind.fromKeyword(head) != null ? Type.ENTITY_KEYWORD
                : ClauseKind.fromKeyword(head) != null ? Type.CLAUSE_KEYWORD : null;
            if (keywordType != null) {
                pos = start + colon + 1;
                return Token.of(keywordType, head, startLine, startCol);
            }
        }
        return Token.of(Type.NAME, text, startLine, startCol);
    }

    private Token readString(int startLine, int startCol) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || src.charAt(pos) == '\n') {
                throw new RuleParseException("Unclosed string literal", startLine, startCol);
            }
            char ch = src.charAt(pos++);
            if (ch == '"') {
                break;
            }
            if (ch == '\\' && pos < src.length()) {
                char esc = src.charAt(pos++);
                sb.append(switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> esc;
                });
            } else {
                sb.append(ch);
            }
        }
        String datatype = null;
        String language = null;
        if (src.startsWith("^^", pos)) {
            pos += 2;
            if (pos < src.length() && src.charAt(pos) == '<') {
                datatype = readDelimited('<', '>', "IRI");
            } else {
                int start = pos;
                while (pos < src.length() && isNamePart(src.charAt(pos))) {
                    pos++;
                }
                datatype = src.substring(start, pos);
            }
            if (datatype.isEmpty()) {
                throw new RuleParseException("Expected a datatype after ^^", line, col());
            }
        } else if (pos + 1 < src.length() && src.charAt(pos) == '@' && Character.isLetter(src.charAt(pos + 1))) {
            int start = ++pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '-')) {
                pos++;
            }
            language = src.substring(start, pos);
        }
        return new Token(Type.STRING, sb.toString(), startLine, startCol, null, null, null, datatype, language);
    }

    private Token readCellReference(int startLine, int startCol) {
        int start = pos++;
        String sheet = null;
        if (pos < src.length() && src.charAt(pos) == '\'') {
            sheet = readDelimited('\'', '\'', "sheet name");
            if (pos >= src.length() || src.charAt(pos) != '!') {
                throw new RuleParseException("Expected '!' after quoted sheet name", line, col());
            }
            pos++;
        }
        String run = readRefRun();
        if (sheet == null && pos < src.length() && src.charAt(pos) == '!') {
            sheet = run;
            pos++;
            run = readRefRun();
        }
        Matcher m = CELL.matcher(run);
        if (!m.matches()) {
            throw new RuleParseException("Invalid cell reference '" + src.substring(start, pos)
                + "'. Use a column and row such as @A5, @A* or @Sheet1!B*", startLine, startCol);
        }
        Integer col = "*".equals(m.group(1)) ? null : SpreadsheetParserService.columnIndex(m.group(1));
        Integer row = "*".equals(m.group(2)) ? null : Integer.valueOf(m.group(2));
        if (row != null && row < 1) {
            throw new RuleParseException("Row numbers start at 1", startLine, startCol);
        }
        return new Token(Type.CELL_REF, src.substring(start, pos), startLine, startCol, sheet, col, row, null, null);
    }

    private String readRefRun() {
        int start = pos;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (Character.isLetterOrDigit(ch) || ch == '*' || ch == '_' || ch == '-' || ch == '.') {
                pos++;
            } else {
                break;
            }
        }
        return src.substring(start, pos);
    }

    private String readDelimited(char open, char close, String what) {
        int startLine = line;
        int startCol = col();
        pos++;
        int start = pos;
        while (pos < src.length() && src.charAt(pos) != close) {
            if (src.charAt(pos) == '\n') {
                break;
            }
            pos++;
        }
        if (pos >= src.length() || src.charAt(pos) != close) {
            throw new RuleParseException("Unclosed " + what + " (missing '" + close + "')", startLine, startCol);
        }
        String text = src.substring(start, pos);
        pos++;
        return text;
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (ch == '\n') {
                line++;
                lineStart = pos + 1;
            } else if (!Character.isWhitespace(ch)) {
                return;
            }
            pos++;
        }
    }

    private int col() {
        return pos - lineStart + 1;
    }

    private static boolean isNameStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isNamePart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.' || ch == ':' || ch == '/' || ch == '#';
    }
}
