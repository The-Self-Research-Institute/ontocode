package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.swrl.model.InferredAxiom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

public final class AssertedAxiomLister {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_WINDOW = 20_000;
    public static final long DEFAULT_MAX_SCAN = 500_000;

    public record Page(long total, int page, int pageSize, boolean truncated, long scanned, long maxScan,
                       List<InferredAxiom> items) {
    }

    private record Candidate(String type, String text, OWLAxiom axiom) {
    }

    private static final Comparator<Candidate> ORDER = Comparator.comparing(Candidate::type).thenComparing(Candidate::text);

    private AssertedAxiomLister() {
    }

    public static int clampPageSize(int requested) {
        if (requested < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    public static AxiomType<?> parseType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        for (AxiomType<?> type : AxiomType.AXIOM_TYPES) {
            if (type.getName().equalsIgnoreCase(wanted) && listable(type)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown axiom type '" + wanted + "'");
    }

    private static boolean listable(AxiomType<?> type) {
        return !AxiomType.SWRL_RULE.equals(type) && !AxiomType.DECLARATION.equals(type);
    }

    public static Page list(OWLOntology ontology, String search, AxiomType<?> only, int page, int requestedPageSize,
                            long maxScan) {
        int pageSize = clampPageSize(requestedPageSize);
        int pageIndex = Math.max(page, 0);
        long window = (long) (pageIndex + 1) * pageSize;
        if (window > MAX_WINDOW) {
            throw new IllegalArgumentException("That page is too far down the list. Narrow the search or the axiom type instead.");
        }
        int keep = (int) window;
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<AxiomType<?>> types = new ArrayList<>();
        if (only != null) {
            types.add(only);
        } else {
            for (AxiomType<?> type : AxiomType.AXIOM_TYPES) {
                if (listable(type)) {
                    types.add(type);
                }
            }
        }
        RuleStructure structure = RuleStructure.of(ontology);
        AxiomWording wording = AxiomWording.of(ontology);
        PriorityQueue<Candidate> best = new PriorityQueue<>(Math.min(keep + 1, 1024), ORDER.reversed());
        long total = 0;
        long scanned = 0;
        boolean truncated = false;
        scan:
        for (AxiomType<?> type : types) {
            for (OWLAxiom axiom : ontology.getAxioms(type)) {
                if (scanned >= maxScan) {
                    truncated = true;
                    break scan;
                }
                scanned++;
                if (InferredAxiomPresenter.isTrivial(axiom) || structure.isArtifact(axiom)) {
                    continue;
                }
                String text = InferredAxiomPresenter.readable(axiom, wording);
                if (needle != null && !text.toLowerCase(Locale.ROOT).contains(needle)
                        && !axiom.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                total++;
                Candidate candidate = new Candidate(type.getName(), text, axiom);
                if (best.size() < keep) {
                    best.add(candidate);
                } else if (ORDER.compare(candidate, best.peek()) < 0) {
                    best.poll();
                    best.add(candidate);
                }
            }
        }
        List<Candidate> sorted = new ArrayList<>(best);
        sorted.sort(ORDER);
        int from = Math.min(pageIndex * pageSize, sorted.size());
        List<InferredAxiom> items = new ArrayList<>();
        for (Candidate candidate : sorted.subList(from, sorted.size())) {
            items.add(InferredAxiomPresenter.format(candidate.axiom(), wording));
        }
        return new Page(total, pageIndex, pageSize, truncated, scanned, maxScan, items);
    }
}
