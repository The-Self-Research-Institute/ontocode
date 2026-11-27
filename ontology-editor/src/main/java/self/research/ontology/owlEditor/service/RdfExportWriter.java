package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.common.xml.XMLUtil;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class RdfExportWriter {
    private RdfExportWriter() {}

    static RDFWriter create(RDFFormat format, Writer out, Map<String, String> prefixes) {
        return Rio.createWriter(format, RDFFormat.RDFXML.equals(format) && !prefixes.isEmpty()
                ? new NamespaceRootWriter(out, prefixes) : out);
    }

    private static final class NamespaceRootWriter extends FilterWriter {
        private static final Pattern ROOT = Pattern.compile("<(?:[^\\s<>/:]+:)?RDF(?=[\\s>])");
        private static final Pattern NAMESPACE = Pattern.compile("\\sxmlns:([^\\s=]+)\\s*=");
        private final Map<String, String> prefixes;
        private StringBuilder header = new StringBuilder();
        private int position;
        private char quote;

        private NamespaceRootWriter(Writer out, Map<String, String> prefixes) {
            super(out);
            this.prefixes = prefixes;
        }

        @Override
        public void write(char[] text, int offset, int length) throws IOException {
            if (header == null) {
                out.write(text, offset, length);
                return;
            }
            header.append(text, offset, length);
            if (position == 0) {
                var root = ROOT.matcher(header);
                if (!root.find()) return;
                position = root.end();
            }
            while (position < header.length()) {
                char current = header.charAt(position++);
                if (quote != 0) {
                    if (current == quote) quote = 0;
                } else if (current == '"' || current == '\'') {
                    quote = current;
                } else if (current == '>') {
                    writeRoot(position - 1);
                    return;
                }
            }
        }

        private void writeRoot(int end) throws IOException {
            String root = header.substring(0, end);
            Set<String> present = new HashSet<>();
            var declarations = NAMESPACE.matcher(root);
            while (declarations.find()) present.add(declarations.group(1));
            out.write(root);
            for (var entry : prefixes.entrySet()) {
                String prefix = entry.getKey();
                if (!prefix.isEmpty() && XMLUtil.isNCName(prefix) && !prefix.equalsIgnoreCase("xml") && !prefix.equalsIgnoreCase("xmlns")
                        && !present.contains(prefix)) {
                    out.write("\n   xmlns:" + prefix + "=\"" + XMLUtil.escapeDoubleQuotedAttValue(entry.getValue()) + "\"");
                }
            }
            out.write(header.substring(end));
            header = null;
        }

        @Override
        public void write(int ch) throws IOException {
            write(new char[] { (char) ch }, 0, 1);
        }

        @Override
        public void write(String text, int offset, int length) throws IOException {
            if (header == null) {
                out.write(text, offset, length);
            } else {
                write(text.substring(offset, offset + length).toCharArray(), 0, length);
            }
        }

        @Override
        public void flush() throws IOException {
            if (header != null && !header.isEmpty()) {
                out.write(header.toString());
                header = null;
            }
            super.flush();
        }
    }
}
