package com.github.brunopacheco1.praecepta;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TrieTreeNode {

    static final String ROOT = "ROOT";
    static final String ANY = "_ANY_";
    static final String NULL = "NULL";

    private final String value;

    private final HitPolicy hitPolicy;

    private int nodeLevel;

    private Map<String, TrieTreeNode> children = new HashMap<>();

    private Collection<Integer> outputs;

    public TrieTreeNode(HitPolicy hitPolicy, String value, int nodeLevel) {
        this.hitPolicy = hitPolicy;
        this.value = value;
        this.nodeLevel = nodeLevel;
        outputs = switch (hitPolicy) {
            case FIRST, COLLECT -> new LinkedHashSet<>();
            case ANY, UNIQUE, RULE_ORDER -> new TreeSet<>();
            case PRIORITY, OUTPUT_ORDER -> new TreeSet<>(Comparator.reverseOrder());
        };
    }

    public List<Integer> getOutputs() {
        return List.copyOf(outputs);
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
    public List<Integer> evaluate(InputString inputString) {
        // End of the chain, you found it.
        if (nodeLevel == inputString.size()) {
            if (outputs.isEmpty()) {
                return List.of();
            }
            return List.copyOf(outputs);
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
        return List.of();
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
            propagateChildrenAndOutputs(possibleValue, inputString);
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
        addOutputs(nodeToCopy.outputs);
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
    private void propagateChildrenAndOutputs(String value, InputString inputString) {
        Collection<TrieTreeNode> nodesToReceiveChildren = List.of(children.get(value));
        if (ANY.equals(value)) {
            nodesToReceiveChildren = children.values();
        }

        var isNoTheEndOfTheBranch = nodeLevel < inputString.size() - 1;
        for (var child : nodesToReceiveChildren) {
            if (isNoTheEndOfTheBranch) {
                child.append(inputString);
            } else {
                child.addOutput(inputString.priority());
            }
        }
    }

    /**
     * In the case of conflicting branches (possible input combinations that could
     * result in more than one output),
     * this method solves the conflict keeping in memory the highest outputs,
     * depending on the hit policy.
     *
     * @param outputs the outputs collection
     */
    public void addOutputs(Collection<Integer> outputs) {
        if (outputs != null) {
            for (var output : outputs) {
                addOutput(output);
            }
        }
    }

    /**
     * In the case of conflicting branches (possible input combinations that could
     * result in more than one output),
     * this method solves the conflict keeping in memory the highest output,
     * depending on the hit policy.
     *
     * @param output the index of an output
     */
    public void addOutput(Integer output) {
        if (output != null) {
            outputs.add(output);
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
            outputs = anyNode.outputs;
        }
        children.values().forEach(TrieTreeNode::compress);

        if (hitPolicy.isFindFirst()) {
            var first = outputs.stream().findFirst();
            if (first.isPresent()) {
                outputs = List.of(first.get());
            }
        }
    }
}
