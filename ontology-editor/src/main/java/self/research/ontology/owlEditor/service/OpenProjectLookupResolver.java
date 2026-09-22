package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
class OpenProjectLookupResolver {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("-(\\d+)$");

    private final OpenProjectService owner;
    private final Map<String, String> typeCache = new HashMap<>();
    private final Map<String, String> priorityCache = new HashMap<>();
    private final Map<String, String> statusCache = new HashMap<>();
    private final AtomicReference<String> parentIdCache = new AtomicReference<>();
    private volatile boolean parentIdResolved = false;

    OpenProjectLookupResolver(OpenProjectService owner) {
        this.owner = owner;
    }

    String resolveTypeHref(String issueType) {
        if (typeCache.isEmpty()) {
            typeCache.putAll(toHrefMap(owner.fetch(owner.hostUrl() + "/api/v3/projects/" + owner.encode(owner.projectId) + "/types")));
        }
        String preferred = typeCache.get(issueType == null ? "" : issueType.trim().toLowerCase());
        if (preferred != null) {
            return preferred;
        }
        String fallback = typeCache.get(owner.defaultType.trim().toLowerCase());
        if (fallback == null) {
            throw new IllegalStateException("OpenProject type not found for \"" + issueType + "\". Available: " + typeCache.keySet());
        }
        log.warn("OpenProject type \"{}\" not enabled on project {}; falling back to \"{}\"", issueType, owner.projectId, owner.defaultType);
        return fallback;
    }

    String resolvePriorityHref(String priority) {
        if (priorityCache.isEmpty()) {
            priorityCache.putAll(toHrefMap(owner.fetch(owner.hostUrl() + "/api/v3/priorities")));
        }
        String href = priorityCache.get(priority.trim().toLowerCase());
        if (href == null) {
            log.warn("OpenProject priority \"{}\" not found on this instance ({}); leaving priority unset", priority, priorityCache.keySet());
        }
        return href;
    }

    String resolveStatusHref() {
        if (owner.configuredStatus == null || owner.configuredStatus.isBlank()) {
            return null;
        }
        if (statusCache.isEmpty()) {
            statusCache.putAll(toHrefMap(owner.fetch(owner.hostUrl() + "/api/v3/statuses")));
        }
        String href = statusCache.get(owner.configuredStatus.trim().toLowerCase());
        if (href == null) {
            log.warn("openproject.status=\"{}\" does not match any OpenProject status ({}); the type default will be used", owner.configuredStatus, statusCache.keySet());
        }
        return href;
    }

    String resolveParentId() {
        if (parentIdResolved) {
            return parentIdCache.get();
        }
        synchronized (this) {
            if (parentIdResolved) {
                return parentIdCache.get();
            }
            String configured = owner.configuredParentId == null ? "" : owner.configuredParentId.trim();
            if (configured.isEmpty()) {
                return finish(null);
            }
            if (configured.matches("[0-9]+")) {
                return finish(configured);
            }
            try {
                JsonNode data = owner.fetch(owner.hostUrl() + "/api/v3/work_packages/" + owner.encode(configured));
                String id = String.valueOf(data.path("id").asInt());
                log.info("OpenProject parent \"{}\" resolved to work package {}", configured, id);
                return finish(id);
            } catch (Exception e) {
                Matcher matcher = TRAILING_NUMBER.matcher(configured);
                if (matcher.find()) {
                    String trailingId = matcher.group(1);
                    log.warn("openproject.parent.id=\"{}\" could not be looked up ({}); assuming work package {} from the identifier suffix. Set openproject.parent.id to the numeric id to remove this guess.",
                            configured, owner.describeError(e), trailingId);
                    return finish(trailingId);
                }
                log.error("openproject.parent.id=\"{}\" could not be resolved to a numeric work package id ({}). Tickets will be created without a parent.",
                        configured, owner.describeError(e));
                return finish(null);
            }
        }
    }

    private String finish(String value) {
        parentIdCache.set(value);
        parentIdResolved = true;
        return value;
    }

    private static Map<String, String> toHrefMap(JsonNode collection) {
        Map<String, String> map = new HashMap<>();
        if (collection == null) {
            return map;
        }
        for (JsonNode element : collection.path("_embedded").path("elements")) {
            String name = element.path("name").asText(null);
            String href = element.path("_links").path("self").path("href").asText(null);
            if (name != null && href != null) {
                map.put(name.trim().toLowerCase(), href);
            }
        }
        return map;
    }
}
