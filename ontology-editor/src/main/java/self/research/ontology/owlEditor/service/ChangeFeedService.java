package self.research.ontology.owlEditor.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ChangeFeedService {


    public record Query(int count, int offset, String type, String author, String text,
                        boolean includeRollbacks, Set<String> viewerIds) {}

    public record Page(List<HistoryChange> changes, boolean hasMore) {}

    private final HistoryChangeRepository repository;

    public ChangeFeedService(HistoryChangeRepository repository) {
        this.repository = repository;
    }

    public Page page(String projectId, Query query) {
        int wanted = Math.max(1, Math.min(query.count(), Limit.FEED_MAX_COUNT.value()));
        int skip = Math.max(0, query.offset());
        List<HistoryChange> matched = new ArrayList<>();
        boolean exhausted = false;
        Sort order = Sort.by(Sort.Direction.DESC, "timestamp").and(Sort.by(Sort.Direction.DESC, "_id"));
        for (int page = 0; page < Limit.FEED_MAX_PAGES.value() && matched.size() <= wanted; page++) {
            List<HistoryChange> batch = repository.findByProjectId(projectId, PageRequest.of(page, Limit.FEED_PAGE_SIZE.value(), order));
            for (HistoryChange change : batch) {
                if (!visible(change, query)) {
                    continue;
                }
                if (skip > 0) {
                    skip--;
                } else {
                    matched.add(change);
                }
            }
            if (batch.size() < Limit.FEED_PAGE_SIZE.value()) {
                exhausted = true;
                break;
            }
        }
        boolean hasMore = matched.size() > wanted || !exhausted;
        List<HistoryChange> result = matched.size() > wanted ? new ArrayList<>(matched.subList(0, wanted)) : matched;
        return new Page(result, hasMore);
    }

    static boolean visible(HistoryChange change, Query query) {
        if (change.isDraft() && !ownedBy(change, query.viewerIds())) {
            return false;
        }
        if (!query.includeRollbacks() && RollbackWorkSupport.isRollbackEntry(change)) {
            return false;
        }
        if (!blank(query.type()) && !bucket(change).equalsIgnoreCase(query.type())) {
            return false;
        }
        if (!blank(query.author()) && !query.author().equalsIgnoreCase(String.valueOf(change.getUserId()))
                && !query.author().equalsIgnoreCase(String.valueOf(change.getUsername()))) {
            return false;
        }
        return blank(query.text()) || matchesText(change, query.text().toLowerCase(Locale.ROOT));
    }

    static String bucket(HistoryChange change) {
        String computed = HistoryClassification.entityType(change.getOperationType());
        return "OTHER".equals(computed) && change.getEntityType() != null ? change.getEntityType() : computed;
    }

    private static boolean ownedBy(HistoryChange change, Set<String> viewerIds) {
        if (viewerIds == null || change.getUserId() == null) {
            return false;
        }
        for (String id : viewerIds) {
            if (id != null && id.equalsIgnoreCase(change.getUserId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesText(HistoryChange change, String needle) {
        return contains(change.getEntityLabel(), needle) || contains(change.getEntityIRI(), needle)
                || contains(change.getDescription(), needle) || contains(change.getUsername(), needle)
                || contains(change.getOperationType(), needle) || contains(change.getOldValue(), needle)
                || contains(change.getNewValue(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
