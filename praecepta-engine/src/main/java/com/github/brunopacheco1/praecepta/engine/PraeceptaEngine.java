package com.github.brunopacheco1.praecepta.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import com.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import com.github.brunopacheco1.praecepta.engine.beans.InputString;
import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import com.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import com.github.brunopacheco1.praecepta.engine.exceptions.InvalidPraeceptaException;
import com.github.brunopacheco1.praecepta.engine.exceptions.MissingOutputException;

public final class PraeceptaEngine<I, O> {

    private static Logger log = Logger.getLogger(PraeceptaEngine.class.getName());

    private final InputStrategy<I> inputStrategy;
    private final OutputStrategy<O> outputStrategy;
    private final AggregationOperator aggregationOperator;
    private final TrieTreeNode root;

    /**
     * Important remark here, the ordering of input keys does not matter to the
     * evaluation,
     * but it matters to the tree distribution. That means, poorly created praecepta
     * or poorly sorted input keys, will result on exponential memory consumption.
     */
    private List<String> inputKeys;

    private final Map<Integer, PraeceptaOutput> outputs = new HashMap<>();

    public PraeceptaEngine(
            HitPolicy hitPolicy,
            InputStrategy<I> inputStrategy,
            OutputStrategy<O> outputStrategy) {
        this(hitPolicy, AggregationOperator.COLLECT, inputStrategy, outputStrategy);
    }

    public PraeceptaEngine(
            HitPolicy hitPolicy,
            AggregationOperator aggregationOperator,
            InputStrategy<I> inputStrategy,
            OutputStrategy<O> outputStrategy) {
        this.aggregationOperator = aggregationOperator;
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
                .toList();
    }

    private void validate(List<Praeceptum> praecepta) {
        var visitor = new EngineAssertionsVisitor();
        root.accept(visitor);

        if (visitor.getShouldHaveRuleIds() > 0) {
            throw new InvalidPraeceptaException();
        }

        if (visitor.getShouldNotHaveRuleIds() > 0) {
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

        log.log(Level.INFO, "Number of branches: {0}", visitor.getPathLengths().size());
        log.log(Level.INFO, "Shortest path: {0}", shortestPath);
        log.log(Level.INFO, "Longest path: {0}", longestPath);
        log.log(Level.INFO, "Average path length: {0}", averageLength);
        log.log(Level.INFO, "Number of nodes: {0}", numberOfNodes);
    }

    private InputString buildInputString(Integer priority, PraeceptaInput input, UnaryOperator<String> sanitizeFunction) {
        var sanitizedValues = getInputValues(input).stream()
                .map(sanitizeFunction)
                .toList();
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

        var resultStream = root.evaluate(inputString)
                .stream()
                .map(outputs::get)
                .map(outputStrategy::transform);

        List<O> result;
        if (AggregationOperator.COLLECT != aggregationOperator) {
            BinaryOperator<O> reduceFunction = switch (aggregationOperator) {
                case SUM -> outputStrategy::sum;
                case MIN -> outputStrategy::min;
                case MAX -> outputStrategy::max;
                default -> throw new IllegalArgumentException("Not expected aggregation");
            };
            result = resultStream
                    .reduce(reduceFunction)
                    .map(List::of)
                    .orElseGet(List::of);
        } else {
            result = resultStream.toList();
        }

        if (result.isEmpty()) {
            throw new MissingOutputException();
        }

        return result;
    }

    private String sanitizeValueForEvaluation(String value) {
        return Optional.ofNullable(value)
                .filter(Predicate.not(String::isBlank))
                .orElse(TrieTreeNode.NULL);
    }

    private List<String> getInputValues(PraeceptaInput input) {
        return inputKeys.stream()
                .map(it -> input.values().get(it))
                .toList();
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
