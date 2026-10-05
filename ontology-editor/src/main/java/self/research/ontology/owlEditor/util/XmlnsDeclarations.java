package self.research.ontology.owlEditor.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class XmlnsDeclarations {

    private static final Pattern DECLARATION = Pattern.compile("xmlns:?([a-zA-Z0-9_-]*)\\s*=\\s*\"([^\"]*)\"");

    private XmlnsDeclarations() {
    }

    public static void mergeRootDeclarations(Map<String, String> namespaces, byte[] headBytes, int length) {
        if (headBytes == null || length <= 0) {
            return;
        }
        try {
            String head = new String(headBytes, 0, length, StandardCharsets.UTF_8);
            int rootTagEnd = head.indexOf('>');
            String rootTag = rootTagEnd >= 0 ? head.substring(0, rootTagEnd) : head;
            Matcher m = DECLARATION.matcher(rootTag);
            int before = namespaces.size();
            while (m.find()) {
                String prefix = m.group(1) == null ? "" : m.group(1);
                String uri = m.group(2);
                if (uri != null && !uri.isBlank()) {
                    namespaces.putIfAbsent(prefix, uri);
                }
            }
            if (namespaces.size() > before) {
                log.info("[NAMESPACES] Recovered {} declared-but-unused xmlns prefixes from the root element",
                        namespaces.size() - before);
            }
        } catch (Exception e) {
            log.debug("[NAMESPACES] Could not scan raw xmlns declarations: {}", e.getMessage());
        }
    }
}
