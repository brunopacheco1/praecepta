package com.github.brunopacheco1.praecepta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class PraeceptaEngine<I, O> {

    private static Logger log = Logger.getLogger(PraeceptaEngine.class.getName());

    private final InputStrategy<I> inputStrategy;
    private final OutputStrategy<O> outputStrategy;

    private final TrieTreeNode root;

    /**
     * Important remark here, the ordering of input keys does not matter to the
     * evaluation,
     * but it matters to the tree distribution. That means, poorly created praecepta
     * or poorly sorted input keys, will result on exponential memory consumption.
     */
    private List<String> inputKeys;

    private final Map<Integer, Output> outputs = new HashMap<>();

    public PraeceptaEngine(
            HitPolicy hitPolicy,
            InputStrategy<I> inputStrategy,
            OutputStrategy<O> outputStrategy) {
        this.inputStrategy = inputStrategy;
        this.outputStrategy = outputStrategy;
        root = new TrieTreeNode(hitPolicy, TrieTreeNode.ROOT, 0);
    }

    /**
     * This method registers praecepta in the engine, for further evaluation.
     *
     * @param praecepta collection of praeceptum (combination of Input/Output
     *                  values), for evaluation.
     */
    public void register(List<Praeceptum> praecepta) {
        inputKeys = sortInputKeysByNumberOfNullValues(praecepta);
        for (Praeceptum praeceptum : praecepta) {
            var inputString = buildInputString(praeceptum.priority(), praeceptum.input(),
                    this::sanitizeValueForAppending);
            root.append(inputString);
            outputs.put(praeceptum.priority(), praeceptum.output());
        }
        root.compress();
        validate(praecepta);
    }

    private List<String> sortInputKeysByNumberOfNullValues(List<Praeceptum> praecepta) {
        var inputsByNumberOfAnyValues = praecepta.stream()
                .map(Praeceptum::input)
                .flatMap(it -> it.values().entrySet().stream())
                .collect(
                        Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() == null ? 1 : 0, Math::addExact));

        return inputsByNumberOfAnyValues.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private void validate(List<Praeceptum> praecepta) {
        var visitor = new EngineAssertionsVisitor();
        root.accept(visitor);

        if (visitor.getShouldHaveOutputs() > 0) {
            throw new InvalidPraeceptaException();
        }

        if (visitor.getShouldNotHaveOutputs() > 0) {
            throw new InvalidPraeceptaException();
        }

        Set<Integer> actualPraecepta = praecepta.stream().map(Praeceptum::priority).collect(Collectors.toSet());
        if (!visitor.getFoundPraecepta().containsAll(actualPraecepta)) {
            throw new InvalidPraeceptaException();
        }

        var shortestPath = visitor.getPathLengths().stream()
                .mapToInt(Integer::intValue)
                .min()
                .orElse(0);

        var longestPath = visitor.getPathLengths().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);

        var averageLength = visitor.getPathLengths().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0D);

        var numberOfNodes = visitor.getNumberOfNodes();

        log.info(String.format("Number of branches: %s%n", visitor.getPathLengths().size()));
        log.info(String.format("Shortest path: %s%n", shortestPath));
        log.info(String.format("Longest path: %s%n", longestPath));
        log.info(String.format("Average path length: %s%n", averageLength));
        log.info(String.format("Number of nodes: %s%n", numberOfNodes));
    }

    private InputString buildInputString(Integer priority, Input input, UnaryOperator<String> sanitizeFunction) {
        var sanitizedValues = getInputValues(input).stream()
                .map(sanitizeFunction)
                .collect(Collectors.toList());
        return new InputString(priority, sanitizedValues);
    }

    private String sanitizeValueForAppending(String value) {
        return Optional.ofNullable(value)
                .filter(Predicate.not(String::isBlank))
                .orElse(TrieTreeNode.ANY);
    }

    /**
     * Given an input containing all dimensions for evaluation, the Engine returns
     * all possible outputs
     * or an exception, if no output was found.
     *
     * @param externalInput the input to be evaluated
     * @return the output collection
     * @throws MissingOutputException thrown if no output is found.
     */
    public List<O> evaluate(I externalInput) {
        var input = inputStrategy.transform(externalInput);
        var inputString = buildInputString(0, input, this::sanitizeValueForEvaluation);
        var result = root.evaluate(inputString).orElseThrow(MissingOutputException::new);
        return result.stream()
                .map(outputs::get)
                .map(outputStrategy::transform)
                .collect(Collectors.toList());
    }

    private String sanitizeValueForEvaluation(String value) {
        return Optional.ofNullable(value)
                .filter(Predicate.not(String::isBlank))
                .orElse(TrieTreeNode.NULL);
    }

    private List<String> getInputValues(Input input) {
        return inputKeys.stream()
                .map(it -> input.values().get(it))
                .collect(Collectors.toList());
    }

    /**
     * If for any reason, you need to infer the TrieTree content, this is the way to
     * go.
     *
     * @param visitor the visitor
     */
    public void accept(TrieTreeVisitor visitor) {
        root.accept(visitor);
    }

    public List<String> getInputs() {
        return List.copyOf(inputKeys);
    }
}