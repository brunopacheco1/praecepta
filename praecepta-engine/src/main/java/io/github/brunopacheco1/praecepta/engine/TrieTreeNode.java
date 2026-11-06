package io.github.brunopacheco1.praecepta.engine;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutable trie used while building a {@link MatchTree}. Each level tests one input column; a path
 * from the root to a leaf describes a region of the input space, and the leaf records every rule
 * (by position in rule order) matching that region.
 *
 * <p>Wildcard ({@link #ANY}) branches are copied into every concrete sibling, so a lookup never
 * backtracks: it follows the concrete branch if present, else the wildcard branch. This copying is
 * what makes memory grow quickly with wildcard-heavy tables; Phase 2 replaces this structure.
 */
final class TrieTreeNode {

    static final String ANY = "_ANY_";

    private final String value;
    private int nodeLevel;
    private Map<String, TrieTreeNode> children = new HashMap<>();
    private SortedSet<Integer> ruleIndices = new TreeSet<>();

    TrieTreeNode(String value, int nodeLevel) {
        this.value = value;
        this.nodeLevel = nodeLevel;
    }

    String value() {
        return value;
    }

    int nodeLevel() {
        return nodeLevel;
    }

    Map<String, TrieTreeNode> children() {
        return children;
    }

    SortedSet<Integer> ruleIndices() {
        return ruleIndices;
    }

    /**
     * Adds a rule. {@code cells} holds one value per level, already sanitised: {@link #ANY} for a
     * wildcard, otherwise one or more comma-separated alternatives.
     */
    void append(List<String> cells, int ruleIndex) {
        for (var possibleValue : splitIntoPossibleValues(cells.get(nodeLevel))) {
            addNodeToTree(possibleValue);
            propagate(possibleValue, cells, ruleIndex);
        }
    }

    private static Set<String> splitIntoPossibleValues(String cell) {
        return Stream.of(cell.split(",")).collect(Collectors.toSet());
    }

    /** Creates the child if missing; a new concrete child inherits a copy of the wildcard branch. */
    private void addNodeToTree(String childValue) {
        if (!children.containsKey(childValue)) {
            var child = new TrieTreeNode(childValue, nodeLevel + 1);
            children.put(childValue, child);
            if (!childValue.equals(ANY) && children.containsKey(ANY)) {
                child.copyFrom(children.get(ANY));
            }
        }
    }

    private void copyFrom(TrieTreeNode source) {
        ruleIndices.addAll(source.ruleIndices);
        for (var sourceChild : source.children.values()) {
            var child = new TrieTreeNode(sourceChild.value, sourceChild.nodeLevel);
            children.put(child.value, child);
            child.copyFrom(sourceChild);
        }
    }

    /** A wildcard cell continues into every child, concrete ones included, to keep lookups backtrack-free. */
    private void propagate(String childValue, List<String> cells, int ruleIndex) {
        Collection<TrieTreeNode> targets = ANY.equals(childValue) ? children.values() : List.of(children.get(childValue));
        boolean lastLevel = nodeLevel == cells.size() - 1;
        for (var child : targets) {
            if (lastLevel) {
                child.ruleIndices.add(ruleIndex);
            } else {
                child.append(cells, ruleIndex);
            }
        }
    }

    /** Collapses chains where a wildcard is the only option; those levels never discriminate. */
    void compress() {
        while (children.size() == 1 && children.containsKey(ANY)) {
            var anyNode = children.get(ANY);
            children = anyNode.children;
            nodeLevel = anyNode.nodeLevel;
            ruleIndices = anyNode.ruleIndices;
        }
        children.values().forEach(TrieTreeNode::compress);
    }
}
