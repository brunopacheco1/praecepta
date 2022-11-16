package com.github.brunopacheco1.praecepta;

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
    private int shouldHaveOutputs;
    private int shouldNotHaveOutputs;

    public Set<Integer> getFoundPraecepta() {
        return Set.copyOf(foundPraecepta);
    }

    public List<Integer> getPathLengths() {
        return List.copyOf(pathLengths);
    }

    public int getNumberOfNodes() {
        return numberOfNodes;
    }

    public int getShouldHaveOutputs() {
        return shouldHaveOutputs;
    }

    public int getShouldNotHaveOutputs() {
        return shouldNotHaveOutputs;
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
            if (node.getOutputs().isEmpty()) {
                shouldHaveOutputs++;
            } else {
                foundPraecepta.addAll(node.getOutputs());
            }
        } else {
            if (!node.getOutputs().isEmpty()) {
                shouldNotHaveOutputs++;
            }
        }

        pathLengthsStack.push(pathLength + 1);
    }

    @Override
    public void finish(TrieTreeNode node) {
        pathLengthsStack.pop();
    }
}
