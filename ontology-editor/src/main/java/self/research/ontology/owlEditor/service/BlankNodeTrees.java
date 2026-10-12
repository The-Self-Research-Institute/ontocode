package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

final class BlankNodeTrees {

    private BlankNodeTrees() {
    }

    static Model direct(Model model) {
        Model direct = new LinkedHashModel();
        for (Statement st : model) {
            if (st.getSubject() instanceof IRI && !(st.getObject() instanceof BNode)) {
                direct.add(st);
            }
        }
        return direct;
    }

    static Model treeOf(Model model, IRI subject) {
        Model tree = new LinkedHashModel();
        Deque<Resource> pending = new ArrayDeque<>();
        Map<Resource, Boolean> seen = new HashMap<>();
        for (Statement st : model.filter(subject, null, null)) {
            if (st.getObject() instanceof BNode node) {
                tree.add(st);
                if (seen.put(node, Boolean.TRUE) == null) {
                    pending.add(node);
                }
            }
        }
        while (!pending.isEmpty()) {
            Resource node = pending.poll();
            for (Statement st : model.filter(node, null, null)) {
                tree.add(st);
                if (st.getObject() instanceof BNode child && seen.put(child, Boolean.TRUE) == null) {
                    pending.add(child);
                }
            }
        }
        return tree;
    }

    static Model closure(Model model, IRI subject) {
        Model closure = new LinkedHashModel();
        for (Statement st : model.filter(subject, null, null)) {
            if (!(st.getObject() instanceof BNode)) {
                closure.add(st);
            }
        }
        closure.addAll(treeOf(model, subject));
        return closure;
    }

    static int depth(Model tree, IRI subject) {
        Map<Resource, Integer> levels = new HashMap<>();
        Deque<Resource> pending = new ArrayDeque<>();
        levels.put(subject, 0);
        pending.add(subject);
        int max = 0;
        while (!pending.isEmpty()) {
            Resource node = pending.poll();
            int level = levels.get(node);
            for (Statement st : tree.filter(node, null, null)) {
                if (st.getObject() instanceof BNode child && !levels.containsKey(child)) {
                    levels.put(child, level + 1);
                    max = Math.max(max, level + 1);
                    pending.add(child);
                }
            }
        }
        return max;
    }
}
