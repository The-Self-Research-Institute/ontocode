package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Item;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;
import self.research.ontology.owlEditor.service.delta.ExactReversal;

import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class RollbackWorkSupport {

    record Work(HistoryChange entry, Plan plan, List<HistoryChange.SubChange> targets, List<Item> skipped,
                boolean completesEntry, boolean subLevel) {}

    static final String INEXACT_DELETE = "This deletion has no exact record (it was made before exact undo existed, or it was too large to record), so it can't be restored automatically";

    enum Presence { REQUIRED, FORBIDDEN, IGNORED }

    static Presence presence(HistoryChange entry, Kind kind, boolean undo) {
        if (kind == Kind.PREFIX || kind == Kind.EXACT || kind == Kind.DOCUMENT || kind == Kind.MERGE) {
            return Presence.IGNORED;
        }
        boolean removed = kind == Kind.DELETE || ExactReversal.deletesEntity(entry);
        boolean added = kind == Kind.CREATE || kind == Kind.RENAME || ExactReversal.createsEntity(entry);
        if (undo) {
            return removed ? Presence.FORBIDDEN : Presence.REQUIRED;
        }
        return added ? Presence.FORBIDDEN : Presence.REQUIRED;
    }

    static boolean isCreation(HistoryChange entry, Kind kind) {
        boolean named = entry.getOperationType() != null && entry.getOperationType().startsWith("create");
        return kind == Kind.CREATE || named && kind == Kind.EXACT;
    }

    static org.eclipse.rdf4j.model.Model insertedTriples(java.util.Collection<HistoryChange> entries) {
        org.eclipse.rdf4j.model.Model inserted = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        for (HistoryChange entry : entries) {
            ExactReversal.deltaOf(entry).ifPresent(delta -> inserted.addAll(delta.inserted()));
        }
        return inserted;
    }

    private RollbackWorkSupport() {
    }

    static boolean isRollbackEntry(HistoryChange entry) {
        String op = entry.getOperationType();
        return ChangeOrigin.ROLLBACK.equals(entry.getSource())
                || (op != null && (op.startsWith("ROLLBACK_") || op.startsWith("REDO_")));
    }

    static HistoryChange.SubChange findSubChange(HistoryChange entry, String subChangeId) {
        if (entry.getSubChanges() == null || subChangeId == null) {
            return null;
        }
        return entry.getSubChanges().stream().filter(sc -> subChangeId.equals(sc.getId())).findFirst().orElse(null);
    }

    static List<HistoryChange> loadByIds(String projectId, List<String> ids,
                                         java.util.function.Function<String, HistoryChange> lookup) {
        List<HistoryChange> entries = new ArrayList<>();
        for (String id : ids) {
            HistoryChange entry = lookup.apply(id);
            if (entry != null && projectId.equals(entry.getProjectId())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    static Work skip(HistoryChange entry, String reason) {
        return new Work(entry, emptyPlan(), List.of(),
                new ArrayList<>(List.of(new Item(entry.getId(), null, entry.getEntityIRI(), entry.getEntityLabel(), null, reason))),
                false, false);
    }

    static Work skipSub(HistoryChange entry, HistoryChange.SubChange sub, String reason) {
        return new Work(entry, emptyPlan(), List.of(), new ArrayList<>(List.of(item(entry, sub, reason))), false, true);
    }

    static Item item(HistoryChange entry, HistoryChange.SubChange sub, String reason) {
        return new Item(entry.getId(), sub.getId(), entry.getEntityIRI(), entry.getEntityLabel(), sub.getPredicate(), reason);
    }

    static Plan emptyPlan() {
        return new Plan(List.of(), List.of(), Set.of(), List.of());
    }

    static List<Item> appliedItems(Work work) {
        List<Item> items = new ArrayList<>();
        if (work.plan().isEmpty()) {
            return work.completesEntry() ? entryItems(work.entry()) : items;
        }
        HistoryChange entry = work.entry();
        Set<String> handled = work.plan().handledSubChangeIds();
        List<HistoryChange.SubChange> handledTargets = work.targets().stream()
                .filter(sc -> handled.contains(sc.getId())).toList();
        boolean perProperty = work.subLevel() || RollbackMutationPlanner.kindOf(entry) == Kind.GROUPED_MODIFY
                || !handledTargets.isEmpty();
        if (perProperty) {
            handledTargets.forEach(sc -> items.add(item(entry, sc, null)));
        } else {
            items.addAll(entryItems(entry));
        }
        return items;
    }

    static List<Item> entryItems(HistoryChange entry) {
        List<HistoryChange.SubChange> subs = entry.getSubChanges() == null ? List.of() : entry.getSubChanges();
        List<HistoryChange.SubChange> open = subs.stream().filter(sc -> !sc.isReverted()).toList();
        List<HistoryChange.SubChange> counted = open.isEmpty() ? subs : open;
        if (counted.isEmpty()) {
            return List.of(new Item(entry.getId(), null, entry.getEntityIRI(), entry.getEntityLabel(), null, null));
        }
        return counted.stream().map(sc -> item(entry, sc, null)).toList();
    }

    static List<Item> unsupportedItems(HistoryChange entry, Plan plan) {
        List<Item> items = new ArrayList<>();
        for (String predicate : plan.unsupported()) {
            items.add(new Item(entry.getId(), null, entry.getEntityIRI(), entry.getEntityLabel(), predicate,
                    "This kind of change can't be undone automatically"));
        }
        return items;
    }
}
