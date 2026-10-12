package self.research.ontology.owlEditor.util;

public final class PerfPhases {

    private final long start = System.nanoTime();
    private long last = start;
    private final StringBuilder phases = new StringBuilder();

    public void mark(String phase) {
        long now = System.nanoTime();
        phases.append(' ').append(phase).append('=').append((now - last) / 1_000_000).append("ms");
        last = now;
    }

    public void add(String phase, long millis) {
        phases.append(' ').append(phase).append('=').append(millis).append("ms");
    }

    public long totalMs() {
        return (System.nanoTime() - start) / 1_000_000;
    }

    public String summary() {
        return (phases.toString().trim() + " total=" + totalMs() + "ms").trim();
    }
}
