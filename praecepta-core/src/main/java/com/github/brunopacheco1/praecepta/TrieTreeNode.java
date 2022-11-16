package com.github.brunopacheco1.praecepta;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TrieTreeNode {

    static final String ROOT = "ROOT";
    static final String ANY = "_ANY_";
    static final String NULL = "NULL";

    private final String value;

    private final HitPolicy hitPolicy;

    private int nodeLevel;

    private HashMap<String, TrieTreeNode> children = new HashMap<>();

    private Queue<Integer> priorities = new PriorityBlockingQueue<>();

    public TrieTreeNode(HitPolicy hitPolicy, String value, int nodeLevel) {
        this.hitPolicy = hitPolicy;
        this.value = value;
        this.nodeLevel = nodeLevel;
    }

    public List<Integer> getPriorities() {
        return List.copyOf(priorities);
    }

    public Map<String, TrieTreeNode> getChildren() {
        return Map.copyOf(children);
    }

    /**
     * Method responsible to do the evaluation, which means if matches the value of
     * a given field,
     * and recursively goes throw all other fields, until the end of the branch.
     *
     * @param inputString This object contains all field values of a given input.
     * @return the output indexes found for a given input
     */
    public Optional<Queue<Integer>> evaluate(InputString inputString) {
        // End of the chain, you found it.
        if (nodeLevel == inputString.size()) {
            return Optional.of(priorities);
        }

        // Do you have the value I look for?
        var nodeLevelValue = inputString.get(nodeLevel);
        if (children.containsKey(nodeLevelValue)) {
            return children.get(nodeLevelValue).evaluate(inputString);
        }

        // Do you have a default path to go through?
        if (children.containsKey(ANY)) {
            return children.get(ANY).evaluate(inputString);
        }

        // Nothing found.
        return Optional.empty();
    }

    /**
     * This method append a inputString (input values and a priority/output index)
     * into the tree,
     * either matching existing branches or creating new ones.
     *
     * @param inputString an object containing values for further matching and a
     *                    output index.
     */
    public void append(InputString inputString) {
        var possibleValues = splitIntoPossibleValues(inputString.get(nodeLevel));
        for (var possibleValue : possibleValues) {
            addNodeToTree(possibleValue);
            propagateChildrenAndPriorities(possibleValue, inputString);
        }
    }

    /**
     * Sometimes the input field value may contain several possible values. In that
     * situation, these values have to be split apart to propagate correctly
     * the tree.
     *
     * @param value string value that may contain several values.
     * @return a collection of possible values of a given string.
     */
    private Set<String> splitIntoPossibleValues(String value) {
        return Stream.of(value.split(",")).collect(Collectors.toSet());
    }

    /**
     * This method check if the value is already a node of the current branch,
     * otherwise adds to it.
     * If there is a default branch (ANY), this branch has to be copied into the
     * newly created branch, to keep the tree consistent.
     *
     * @param value string object that represents a possible value of a given input
     *              field.
     */
    private void addNodeToTree(String value) {
        if (!children.containsKey(value)) {
            var child = new TrieTreeNode(hitPolicy, value, nodeLevel + 1);
            children.put(child.value, child);
            if (!value.equals(ANY) && children.containsKey(ANY)) {
                child.copyNode(children.get(ANY));
            }
        }
    }

    /**
     * This method copies the content of a given node object into the current one.
     *
     * @param nodeToCopy node to copy from.
     */
    private void copyNode(TrieTreeNode nodeToCopy) {
        addPriorities(nodeToCopy.priorities);
        for (var otherChild : nodeToCopy.children.values()) {
            var child = new TrieTreeNode(hitPolicy, otherChild.value, otherChild.nodeLevel);
            children.put(child.value, child);
            child.copyNode(otherChild);
        }
    }

    /**
     * After adding the value to the tree, whenever necessary, this method is
     * responsible to move on the propagation
     * of the next values. If the newly created branch is a default branch(ANY), all
     * existing nodes, including the created one,
     * will receive the following values in order to keep the tree consistent.
     *
     * @param value       the current value, previously added to the tree.
     * @param inputString the inputString holding all input values and the output
     *                    index.
     */
    private void propagateChildrenAndPriorities(String value, InputString inputString) {
        Collection<TrieTreeNode> nodesToReceiveChildren = List.of(children.get(value));
        if (ANY.equals(value)) {
            nodesToReceiveChildren = children.values();
        }

        var isNoTheEndOfTheBranch = nodeLevel < inputString.size() - 1;
        for (var child : nodesToReceiveChildren) {
            if (isNoTheEndOfTheBranch) {
                child.append(inputString);
            } else {
                child.addPriority(inputString.priority());
            }
        }
    }

    /**
     * In the case of conflicting branches (possible input combinations that could
     * result in more than one priority),
     * this method solves the conflict keeping in memory the highest priorities,
     * depending on the hit policy.
     *
     * @param priorities the priorities queue
     */
    public void addPriorities(Queue<Integer> priorities) {
        if (priorities != null) {
            for (var priority : priorities) {
                addPriority(priority);
            }
        }
    }

    /**
     * In the case of conflicting branches (possible input combinations that could
     * result in more than one priority),
     * this method solves the conflict keeping in memory the highest priority,
     * depending on the hit policy.
     *
     * @param priority the priority or index of an output
     */
    public void addPriority(Integer priority) {
        if (priority != null && !this.priorities.contains(priority)) {
            if (HitPolicy.COLLECT == hitPolicy || this.priorities.isEmpty()) {
                this.priorities.add(priority);
            } else {
                var existingPriority = priorities.poll();
                if (priority.compareTo(existingPriority) < 0) {
                    priorities.add(priority);
                } else {
                    priorities.add(existingPriority);
                }
            }
        }
    }

    /**
     * The entry point to accept and inspect the tree content.
     *
     * @param visitor a visitor object responsible to visit all tree nodes.
     */
    public void accept(TrieTreeVisitor visitor) {
        visitor.visit(this);
        for (var node : children.values()) {
            node.accept(visitor);
        }
        visitor.finish(this);
    }

    /**
     * In order to improve performance and memory consumption,
     * this method removes ANY nodes when they are the only option.
     */
    public void compress() {
        while (children.size() == 1 && children.containsKey(ANY)) {
            var anyNode = children.get(ANY);
            children = anyNode.children;
            nodeLevel = anyNode.nodeLevel;
            priorities = anyNode.priorities;
        }
        children.values().forEach(TrieTreeNode::compress);
    }
}
