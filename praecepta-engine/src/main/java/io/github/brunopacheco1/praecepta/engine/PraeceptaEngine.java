package io.github.brunopacheco1.praecepta.engine;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
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
 * immutable. Matching uses one bitmask per column value, so memory grows linearly with the table.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public final class PraeceptaEngine<I, O> {

    private static final System.Logger LOG = System.getLogger(PraeceptaEngine.class.getName());

    private final HitPolicy hitPolicy;
    private final List<String> inputs;
    private final BitsetIndex index;
    private final InputStrategy<I> inputStrategy;
    private final BitsetIndex.ColumnReader<I> extractorReader;
    private final BitsetIndex.ColumnReader<Map<String, String>> mapReader;
    /** Per bit: the rule's output, and the same as a singleton result. */
    private final Object[] outputByBit;
    private final List<O>[] singleByBit;
    /** Per bit: the rule's identifier, for violation messages. */
    private final Integer[] idByBit;
    /** ANY: per bit, the mask of rules whose outputs equal this rule's. */
    private final long[][] sameOutputsByBit;
    private final BinaryOperator<O> reduce;
    private final ValidationReport report;

    @SuppressWarnings("unchecked")
    private PraeceptaEngine(Builder<I, O> builder) {
        hitPolicy = builder.hitPolicy;
        var rules = List.copyOf(builder.rules);

        var columns = builder.inputStrategy == null ? List.copyOf(builder.extractors.keySet()) : columnsUsedBy(rules);
        if (builder.inputStrategy == null) {
            rejectUndeclaredColumns(rules, builder.extractors.keySet());
        }
        inputs = columns;
        var evaluationOrder = orderByWildcards(columns, rules);

        // Bit order makes "lowest set bit" the winner: rule order, or priority order (ties keep rule order).
        var ranks = builder.outputPriority.isEmpty() ? null : priorityRanks(rules, builder.outputPriority);
        int[] bitToRule = java.util.stream.IntStream.range(0, rules.size()).boxed()
                .sorted(ranks == null ? Comparator.naturalOrder() : Comparator.comparingInt((Integer r) -> ranks[r]))
                .mapToInt(Integer::intValue)
                .toArray();
        var rows = rules.stream()
                .map(rule -> evaluationOrder.stream().map(column -> rule.input().values().get(column)).toList())
                .toList();
        var cells = BitsetIndex.parseCells(rows, evaluationOrder.size());
        index = new BitsetIndex(cells, evaluationOrder.size(), bitToRule);

        inputStrategy = builder.inputStrategy;
        if (inputStrategy == null) {
            Function<? super I, String>[] byColumn = evaluationOrder.stream().map(builder.extractors::get).toArray(Function[]::new);
            extractorReader = (input, column) -> byColumn[column].apply(input);
            mapReader = null;
        } else {
            var byColumn = evaluationOrder.toArray(String[]::new);
            mapReader = (values, column) -> values.get(byColumn[column]);
            extractorReader = null;
        }

        int n = rules.size();
        outputByBit = new Object[n];
        singleByBit = new List[n];
        idByBit = new Integer[n];
        for (int bit = 0; bit < n; bit++) {
            var rule = rules.get(bitToRule[bit]);
            var output = builder.outputStrategy.transform(rule.output());
            outputByBit[bit] = output;
            singleByBit[bit] = List.of(output);
            idByBit[bit] = rule.priority();
        }
        sameOutputsByBit = hitPolicy == HitPolicy.ANY ? sameOutputMasks(rules, bitToRule, index.words()) : null;
        reduce = reducer(builder.aggregation, builder.outputStrategy);

        report = new ValidationReport(TableAnalysis.analyse(index, cells, rules, hitPolicy).stream()
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
        long[] matches = inputStrategy == null
                ? index.match(input, extractorReader)
                : index.match(inputStrategy.transform(input).values(), mapReader);
        if (matches == null) {
            return List.of();
        }
        return switch (hitPolicy) {
            case FIRST, PRIORITY -> singleByBit[BitsetIndex.first(matches)];
            case UNIQUE -> {
                int bit = BitsetIndex.first(matches);
                if (BitsetIndex.next(matches, bit + 1) >= 0) {
                    throw violation(matches);
                }
                yield singleByBit[bit];
            }
            case ANY -> {
                int bit = BitsetIndex.first(matches);
                var same = sameOutputsByBit[bit];
                for (int w = 0; w < matches.length; w++) {
                    if ((matches[w] & ~same[w]) != 0) {
                        throw violation(matches);
                    }
                }
                yield singleByBit[bit];
            }
            case RULE_ORDER, OUTPUT_ORDER, COLLECT -> {
                var selected = new Object[BitsetIndex.count(matches)];
                int i = 0;
                for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
                    selected[i++] = outputByBit[bit];
                }
                if (reduce != null) {
                    var total = (O) selected[0];
                    for (int k = 1; k < selected.length; k++) {
                        total = reduce.apply(total, (O) selected[k]);
                    }
                    yield List.of(total);
                }
                yield (List<O>) List.of(selected);
            }
        };
    }

    private HitPolicyViolationException violation(long[] matches) {
        var ids = new ArrayList<Integer>();
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            ids.add(idByBit[bit]);
        }
        return new HitPolicyViolationException(hitPolicy, ids);
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

    private static long[][] sameOutputMasks(List<Praeceptum> rules, int[] bitToRule, int words) {
        var byOutput = new HashMap<Object, long[]>();
        for (int bit = 0; bit < bitToRule.length; bit++) {
            BitsetIndex.set(byOutput.computeIfAbsent(rules.get(bitToRule[bit]).output(), o -> new long[words]), bit);
        }
        var masks = new long[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            masks[bit] = byOutput.get(rules.get(bitToRule[bit]).output());
        }
        return masks;
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
