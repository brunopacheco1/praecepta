package io.github.brunopacheco1.praecepta.engine;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * A decision table evaluated with strict DMN 1.3 hit-policy semantics.
 *
 * <pre>{@code
 * var engine = PraeceptaEngine.<Order, Discount>builder()
 *         .hitPolicy(HitPolicy.FIRST)
 *         .input("country", Order::country)
 *         .input("segment", Order::segment)
 *         .output(output -> new Discount(output.values().get("discount")))
 *         .rules(loader.getPraecepta())
 *         .build();
 *
 * List<Discount> discounts = engine.evaluate(order); // empty if no rule matches
 * }</pre>
 *
 * <p>Instances are immutable and safe to share between threads. Outputs are converted once, when the
 * engine is built, and the same instances are returned by every evaluation, so output types should be
 * immutable.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public final class PraeceptaEngine<I, O> {

    private static final System.Logger LOG = System.getLogger(PraeceptaEngine.class.getName());

    private record Violation(List<Integer> ruleIds) {
    }

    private final HitPolicy hitPolicy;
    private final List<String> inputs;
    private final MatchTree tree;
    private final InputStrategy<I> inputStrategy;
    private final MatchTree.ColumnReader<I> extractorReader;
    private final MatchTree.ColumnReader<Map<String, String>> mapReader;
    /** Per leaf: the {@code List<O>} result, or a {@link Violation}. */
    private final Object[] results;
    private final ValidationReport report;

    private PraeceptaEngine(Builder<I, O> builder) {
        hitPolicy = builder.hitPolicy;
        var rules = List.copyOf(builder.rules);

        var columns = builder.inputStrategy == null ? List.copyOf(builder.extractors.keySet()) : columnsUsedBy(rules);
        if (builder.inputStrategy == null) {
            rejectUndeclaredColumns(rules, builder.extractors.keySet());
        }
        inputs = columns;
        var levels = orderByWildcards(columns, rules);

        var rows = rules.stream().map(rule -> levels.stream().map(column -> rule.input().values().get(column)).toList()).toList();
        tree = MatchTree.build(levels.size(), rows);

        inputStrategy = builder.inputStrategy;
        if (inputStrategy == null) {
            @SuppressWarnings("unchecked")
            Function<? super I, String>[] byLevel = levels.stream().map(builder.extractors::get).toArray(Function[]::new);
            extractorReader = (input, level) -> byLevel[level].apply(input);
            mapReader = null;
        } else {
            var byLevel = levels.toArray(String[]::new);
            mapReader = (values, level) -> values.get(byLevel[level]);
            extractorReader = null;
        }

        var outputs = rules.stream().map(rule -> builder.outputStrategy.transform(rule.output())).toList();
        var ranks = builder.outputPriority.isEmpty() ? null : priorityRanks(rules, builder.outputPriority);
        var reduce = reducer(builder.aggregation, builder.outputStrategy);

        results = new Object[tree.leafCount()];
        var selected = new BitSet(rules.size());
        var issues = new LinkedHashSet<Issue>();
        for (int leaf = 0; leaf < results.length; leaf++) {
            results[leaf] = resolve(tree.matches(leaf), rules, outputs, ranks, reduce, selected, issues);
        }
        if (hitPolicy == HitPolicy.FIRST || hitPolicy == HitPolicy.PRIORITY) {
            for (int i = selected.nextClearBit(0); i < rules.size(); i = selected.nextClearBit(i + 1)) {
                issues.add(new Issue(Kind.UNREACHABLE_RULE, List.of(rules.get(i).priority())));
            }
        }
        report = new ValidationReport(issues.stream()
                .sorted(Comparator.comparing(Issue::severity).reversed())
                .toList());

        if (builder.failOn != null && report.hasIssuesAtLeast(builder.failOn)) {
            throw new InvalidDecisionTableException(report);
        }
        if (!report.isEmpty()) {
            LOG.log(Level.WARNING, "Decision table has {0} validation issue(s):\n{1}", report.issues().size(), report);
        }
    }

    public static <I, O> Builder<I, O> builder() {
        return new Builder<>();
    }

    /**
     * Evaluates the decision table.
     *
     * @return the outputs selected by the hit policy: at most one for single-hit policies (and for
     *         aggregations), several for multi-hit ones; empty if no rule matches
     * @throws HitPolicyViolationException if the input matches several rules under UNIQUE, or rules
     *                                     with different outputs under ANY
     */
    @SuppressWarnings("unchecked")
    public List<O> evaluate(I input) {
        int leaf = inputStrategy == null
                ? tree.find(input, extractorReader)
                : tree.find(inputStrategy.transform(input).values(), mapReader);
        if (leaf == MatchTree.NO_MATCH) {
            return List.of();
        }
        var result = results[leaf];
        if (result instanceof Violation violation) {
            throw new HitPolicyViolationException(hitPolicy, violation.ruleIds());
        }
        return (List<O>) result;
    }

    public HitPolicy hitPolicy() {
        return hitPolicy;
    }

    /** Input column names, as declared (or, with an {@link InputStrategy}, as used by the rules). */
    public List<String> inputs() {
        return inputs;
    }

    /** Findings of the static analysis performed at build time. */
    public ValidationReport validationReport() {
        return report;
    }

    // ---- hit policies ----------------------------------------------------------------------

    private Object resolve(int[] matches, List<Praeceptum> rules, List<O> outputs, int[] ranks,
            BinaryOperator<O> reduce, BitSet selected, Collection<Issue> issues) {
        if (matches.length == 0) {
            return List.of();
        }
        return switch (hitPolicy) {
            case UNIQUE -> {
                markAll(matches, selected);
                if (matches.length > 1) {
                    var ids = ids(matches, rules);
                    issues.add(new Issue(Kind.OVERLAPPING_RULES, ids));
                    yield new Violation(ids);
                }
                yield List.of(outputs.get(matches[0]));
            }
            case ANY -> {
                markAll(matches, selected);
                var first = rules.get(matches[0]).output();
                for (int m : matches) {
                    if (!Objects.equals(rules.get(m).output(), first)) {
                        var ids = ids(matches, rules);
                        issues.add(new Issue(Kind.CONFLICTING_OUTPUTS, ids));
                        yield new Violation(ids);
                    }
                }
                yield List.of(outputs.get(matches[0]));
            }
            case FIRST -> {
                selected.set(matches[0]);
                yield List.of(outputs.get(matches[0]));
            }
            case PRIORITY -> {
                int best = matches[0];
                for (int m : matches) {
                    if (ranks[m] < ranks[best]) {
                        best = m;
                    }
                }
                selected.set(best);
                yield List.of(outputs.get(best));
            }
            case OUTPUT_ORDER -> {
                markAll(matches, selected);
                yield Arrays.stream(matches).boxed()
                        .sorted(Comparator.comparingInt(m -> ranks[m])) // stable: ties keep rule order
                        .map(outputs::get)
                        .toList();
            }
            case RULE_ORDER -> {
                markAll(matches, selected);
                yield Arrays.stream(matches).mapToObj(outputs::get).toList();
            }
            case COLLECT -> {
                markAll(matches, selected);
                var collected = Arrays.stream(matches).mapToObj(outputs::get).toList();
                yield reduce == null ? collected : List.of(collected.stream().reduce(reduce).orElseThrow());
            }
        };
    }

    private static void markAll(int[] matches, BitSet selected) {
        for (int m : matches) {
            selected.set(m);
        }
    }

    private static List<Integer> ids(int[] matches, List<Praeceptum> rules) {
        return Arrays.stream(matches).mapToObj(m -> rules.get(m).priority()).toList();
    }

    /** Dense rank per rule: lower means higher priority; equal outputs share a rank. */
    private static int[] priorityRanks(List<Praeceptum> rules, Map<String, List<String>> priority) {
        var keys = new int[rules.size()][];
        for (int r = 0; r < rules.size(); r++) {
            var key = new int[priority.size()];
            int c = 0;
            for (var column : priority.entrySet()) {
                var value = rules.get(r).output().values().get(column.getKey());
                int index = column.getValue().indexOf(value);
                if (index < 0) {
                    throw new InvalidDecisionTableException("Rule %s: output %s=%s is not in its priority list %s"
                            .formatted(rules.get(r).priority(), column.getKey(), value, column.getValue()));
                }
                key[c++] = index;
            }
            keys[r] = key;
        }
        Comparator<int[]> lexicographic = java.util.Arrays::compare;
        var distinct = new TreeSet<>(lexicographic);
        distinct.addAll(Arrays.asList(keys));
        var ordered = new ArrayList<>(distinct);
        var ranks = new int[rules.size()];
        for (int r = 0; r < rules.size(); r++) {
            ranks[r] = Collections.binarySearch(ordered, keys[r], lexicographic);
        }
        return ranks;
    }

    private static <O> BinaryOperator<O> reducer(AggregationOperator aggregation, OutputStrategy<O> strategy) {
        if (aggregation == null) {
            return null;
        }
        return switch (aggregation) {
            case SUM -> strategy::sum;
            case MIN -> strategy::min;
            case MAX -> strategy::max;
        };
    }

    // ---- columns -----------------------------------------------------------------------------

    private static List<String> columnsUsedBy(List<Praeceptum> rules) {
        var columns = new TreeSet<String>();
        rules.forEach(rule -> columns.addAll(rule.input().values().keySet()));
        return List.copyOf(columns);
    }

    private static void rejectUndeclaredColumns(List<Praeceptum> rules, Set<String> declared) {
        for (var rule : rules) {
            for (var cell : rule.input().values().entrySet()) {
                if (!declared.contains(cell.getKey()) && cell.getValue() != null && !cell.getValue().isBlank()) {
                    throw new InvalidDecisionTableException("Rule %s tests column '%s', which has no declared input"
                            .formatted(rule.priority(), cell.getKey()));
                }
            }
        }
    }

    /**
     * Columns with fewer wildcards first. Evaluation does not depend on the order, but the trie's size
     * does: testing selective columns first keeps wildcard copies small.
     */
    private static List<String> orderByWildcards(List<String> columns, List<Praeceptum> rules) {
        var wildcards = new LinkedHashMap<String, Integer>();
        for (var column : columns) {
            int count = 0;
            for (var rule : rules) {
                var cell = rule.input().values().get(column);
                if (cell == null || cell.isBlank()) {
                    count++;
                }
            }
            wildcards.put(column, count);
        }
        return wildcards.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()) // stable: ties keep declaration order
                .map(Map.Entry::getKey)
                .toList();
    }

    // ---- builder -------------------------------------------------------------------------------

    /**
     * Configures and builds a {@link PraeceptaEngine}. Inputs are bound either per column with
     * {@link #input(String, Function)} (fastest) or all at once with {@link #inputs(InputStrategy)}.
     */
    public static final class Builder<I, O> {
        private HitPolicy hitPolicy;
        private AggregationOperator aggregation;
        private final Map<String, Function<? super I, String>> extractors = new LinkedHashMap<>();
        private InputStrategy<I> inputStrategy;
        private OutputStrategy<O> outputStrategy;
        private final Map<String, List<String>> outputPriority = new LinkedHashMap<>();
        private List<Praeceptum> rules;
        private Severity failOn;

        private Builder() {
        }

        public Builder<I, O> hitPolicy(HitPolicy hitPolicy) {
            this.hitPolicy = Objects.requireNonNull(hitPolicy, "hitPolicy");
            return this;
        }

        /** Aggregates COLLECT results into a single output, using the output strategy's sum/min/max. */
        public Builder<I, O> aggregation(AggregationOperator aggregation) {
            this.aggregation = Objects.requireNonNull(aggregation, "aggregation");
            return this;
        }

        /** Binds an input column to a getter. Repeat for every column the rules test. */
        public Builder<I, O> input(String column, Function<? super I, String> extractor) {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(extractor, "extractor");
            if (extractors.putIfAbsent(column, extractor) != null) {
                throw new IllegalArgumentException("Input column '%s' is declared twice".formatted(column));
            }
            return this;
        }

        /** Converts each input to a map of column values. Simpler than per-column binding, but slower. */
        public Builder<I, O> inputs(InputStrategy<I> inputStrategy) {
            this.inputStrategy = Objects.requireNonNull(inputStrategy, "inputStrategy");
            return this;
        }

        public Builder<I, O> output(OutputStrategy<O> outputStrategy) {
            this.outputStrategy = Objects.requireNonNull(outputStrategy, "outputStrategy");
            return this;
        }

        /**
         * DMN output value list for PRIORITY and OUTPUT_ORDER: the allowed values of an output column,
         * highest priority first. Columns are compared in the order they are declared.
         */
        public Builder<I, O> outputPriority(String outputColumn, List<String> valuesHighestFirst) {
            Objects.requireNonNull(outputColumn, "outputColumn");
            var values = List.copyOf(valuesHighestFirst);
            if (new HashSet<>(values).size() != values.size()) {
                throw new IllegalArgumentException("Priority list for '%s' has duplicates: %s".formatted(outputColumn, values));
            }
            if (outputPriority.putIfAbsent(outputColumn, values) != null) {
                throw new IllegalArgumentException("Priority list for '%s' is declared twice".formatted(outputColumn));
            }
            return this;
        }

        /** The rules, in DMN rule order. */
        public Builder<I, O> rules(List<Praeceptum> rules) {
            this.rules = List.copyOf(rules);
            return this;
        }

        /** Fail {@link #build()} with an {@link InvalidDecisionTableException} if validation finds issues this severe. */
        public Builder<I, O> failOn(Severity severity) {
            this.failOn = Objects.requireNonNull(severity, "severity");
            return this;
        }

        public PraeceptaEngine<I, O> build() {
            require(hitPolicy != null, "a hit policy is required");
            require(outputStrategy != null, "an output strategy is required");
            require(rules != null, "rules are required");
            require(extractors.isEmpty() != (inputStrategy == null),
                    "bind inputs either per column with input(...) or with inputs(...), not both and not neither");
            require(aggregation == null || hitPolicy == HitPolicy.COLLECT,
                    "aggregation is only allowed with the COLLECT hit policy");
            boolean needsPriority = hitPolicy == HitPolicy.PRIORITY || hitPolicy == HitPolicy.OUTPUT_ORDER;
            require(!needsPriority || !outputPriority.isEmpty(), hitPolicy + " requires outputPriority(...) lists");
            require(needsPriority || outputPriority.isEmpty(), "outputPriority(...) only applies to PRIORITY and OUTPUT_ORDER");
            return new PraeceptaEngine<>(this);
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }
    }
}
