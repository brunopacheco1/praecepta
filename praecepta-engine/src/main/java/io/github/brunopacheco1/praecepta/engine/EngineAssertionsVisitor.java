package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EngineAssertionsVisitor implements TrieTreeVisitor {

    private final Set<Integer> foundPraecepta = new HashSet<>();
    private final List<Integer> pathLengths = new ArrayList<>();
    private final Deque<Integer> pathLengthsStack = new ArrayDeque<>();
    private int numberOfNodes;
    private int shouldHaveRuleIds;
    private int shouldNotHaveRuleIds;

    public Set<Integer> getFoundPraecepta() {
        return Set.copyOf(foundPraecepta);
    }

    public List<Integer> getPathLengths() {
        return List.copyOf(pathLengths);
    }

    public int getNumberOfNodes() {
        return numberOfNodes;
    }

    public int getShouldHaveRuleIds() {
        return shouldHaveRuleIds;
    }

    public int getShouldNotHaveRuleIds() {
        return shouldNotHaveRuleIds;
    }

    @Override
    public void visit(TrieTreeNode node) {
        var pathLength = 0;
        numberOfNodes++;
        if (!pathLengthsStack.isEmpty()) {
            pathLength = pathLengthsStack.peek();
        }

        if (node.getChildren().isEmpty()) {
            pathLengths.add(pathLength);
            if (node.getRuleIds().isEmpty()) {
                shouldHaveRuleIds++;
            } else {
                foundPraecepta.addAll(node.getRuleIds());
            }
        } else {
            if (!node.getRuleIds().isEmpty()) {
                shouldNotHaveRuleIds++;
            }
        }

        pathLengthsStack.push(pathLength + 1);
    }

    @Override
    public void finish(TrieTreeNode node) {
        pathLengthsStack.pop();
    }
}
