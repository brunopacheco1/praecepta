package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Immutable lookup structure: finds the leaf (input region) an input falls into. Each leaf carries
 * the positions, in rule order, of every rule matching that region. Leaves with identical match
 * sets are shared, so leaf ids double as "distinct match set" ids.
 */
final class MatchTree {

    /** Reads the value of the column tested at {@code level} from an input. */
    @FunctionalInterface
    interface ColumnReader<T> {
        String read(T input, int level);
    }

    private static final class Node {
        final int level;
        final Map<String, Node> children;
        final Node any;
        final int leaf;

        Node(int level, Map<String, Node> children, Node any, int leaf) {
            this.level = level;
            this.children = children;
            this.any = any;
            this.leaf = leaf;
        }
    }

    static final int NO_MATCH = -1;

    private final Node root;
    private final List<int[]> leaves;

    private MatchTree(Node root, List<int[]> leaves) {
        this.root = root;
        this.leaves = leaves;
    }

    /**
     * @param rows one row per rule, in rule order; each row holds the cell of every level (column
     *             in level order), null or blank meaning "any"
     */
    static MatchTree build(int levels, List<List<String>> rows) {
        var leaves = new ArrayList<int[]>();
        var leafIds = new HashMap<List<Integer>, Integer>();
        if (rows.isEmpty()) {
            return new MatchTree(null, leaves);
        }
        if (levels == 0) {
            var all = new ArrayList<Integer>();
            for (int i = 0; i < rows.size(); i++) {
                all.add(i);
            }
            return new MatchTree(new Node(0, Map.of(), null, leafId(all, leaves, leafIds)), leaves);
        }
        var trie = new TrieTreeNode("ROOT", 0);
        for (int i = 0; i < rows.size(); i++) {
            var cells = rows.get(i).stream()
                    .map(cell -> Optional.ofNullable(cell).filter(Predicate.not(String::isBlank)).orElse(TrieTreeNode.ANY))
                    .toList();
            trie.append(cells, i);
        }
        trie.compress();
        return new MatchTree(freeze(trie, levels, leaves, leafIds), leaves);
    }

    private static Node freeze(TrieTreeNode node, int levels, List<int[]> leaves, Map<List<Integer>, Integer> leafIds) {
        if (node.nodeLevel() == levels) {
            return new Node(levels, Map.of(), null, leafId(List.copyOf(node.ruleIndices()), leaves, leafIds));
        }
        var children = new HashMap<String, Node>();
        Node any = null;
        for (var child : node.children().entrySet()) {
            var frozen = freeze(child.getValue(), levels, leaves, leafIds);
            if (child.getKey().equals(TrieTreeNode.ANY)) {
                any = frozen;
            } else {
                children.put(child.getKey(), frozen);
            }
        }
        return new Node(node.nodeLevel(), Map.copyOf(children), any, NO_MATCH);
    }

    private static int leafId(List<Integer> matches, List<int[]> leaves, Map<List<Integer>, Integer> leafIds) {
        return leafIds.computeIfAbsent(matches, key -> {
            leaves.add(key.stream().mapToInt(Integer::intValue).toArray());
            return leaves.size() - 1;
        });
    }

    /** Leaf id of the region {@code input} falls into, or {@link #NO_MATCH}. */
    <T> int find(T input, ColumnReader<T> reader) {
        var node = root;
        if (node == null) {
            return NO_MATCH;
        }
        while (node.leaf == NO_MATCH) {
            var value = reader.read(input, node.level);
            var next = value == null ? null : node.children.get(value); // blank values never match a key
            if (next == null) {
                next = node.any;
                if (next == null) {
                    return NO_MATCH;
                }
            }
            node = next;
        }
        return node.leaf;
    }

    int leafCount() {
        return leaves.size();
    }

    /** Positions (rule order, ascending) of the rules matching a leaf's region. */
    int[] matches(int leaf) {
        return leaves.get(leaf);
    }
}
