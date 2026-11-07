/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.lang.System.Logger.Level;
import java.time.LocalDate;
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
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

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
 * <p>Cells are DMN FEEL unary tests, parsed per column type: {@code "PT","ES"} (or unquoted
 * {@code PT,ES}), {@code not("FR")}, {@code >= 18}, {@code [18..65)}, {@code < date("2025-01-01")},
 * {@code null}, and {@code -} or blank for any value. See {@code CellParser}.
 *
 * <p>A whole DMN decision table (hit policy, column types, value lists and rules) can be loaded with
 * {@code DmnReader} and passed to {@link Builder#table}; inputs are then bound as usual:
 *
 * <pre>{@code
 * var engine = PraeceptaEngine.<Applicant, String>builder()
 *         .table(DmnReader.read(stream))
 *         .input("country", Applicant::country)
 *         .numberInput("age", Applicant::age)
 *         .output(output -> output.values().get("tier"))
 *         .build();
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

        // columns and their types
        var types = new LinkedHashMap<String, ColumnType>();
        var allowedValues = new LinkedHashMap<>(builder.allowedValues);
        if (builder.inputStrategy == null) {
            builder.extractors.forEach((column, binding) -> types.put(column, binding.type()));
            rejectUndeclaredColumns(rules, types.keySet());
            // an enum is a closed set of values: it is the column's input value list unless one is declared
            builder.extractors.forEach((column, binding) -> {
                if (binding.enumValues() != null) {
                    allowedValues.putIfAbsent(column, quotedList(List.of(binding.enumValues())));
                }
            });
        } else {
            if (builder.tableTypes != null) {
                types.putAll(builder.tableTypes);
            }
            columnsUsedBy(rules).forEach(column -> types.putIfAbsent(column, ColumnType.STRING));
            builder.allowedValues.keySet().forEach(column -> types.putIfAbsent(column, ColumnType.STRING));
            types.putAll(builder.columnTypes);
        }
        for (var column : allowedValues.keySet()) {
            if (!types.containsKey(column)) {
                throw new InvalidDecisionTableException("Input values declared for unknown column '%s'".formatted(column));
            }
        }
        inputs = List.copyOf(types.keySet());
        var cells = parseCells(rules, types);
        var evaluationOrder = orderByWildcards(inputs, cells);

        // Bit order makes "lowest set bit" the winner: rule order, or priority order (ties keep rule order).
        var ranks = builder.outputPriority.isEmpty() ? null : priorityRanks(rules, builder.outputPriority);
        int[] bitToRule = java.util.stream.IntStream.range(0, rules.size()).boxed()
                .sorted(ranks == null ? Comparator.naturalOrder() : Comparator.comparingInt((Integer r) -> ranks[r]))
                .mapToInt(Integer::intValue)
                .toArray();
        int words = BitsetIndex.wordsFor(rules.size());
        var columnIndexes = evaluationOrder.stream()
                .map(column -> new ColumnIndex(column, types.get(column),
                        Arrays.stream(bitToRule).mapToObj(rule -> cells.get(column).get(rule)).toList(),
                        allowedTests(column, types.get(column), allowedValues.get(column)), words,
                        builder.inputStrategy == null ? builder.extractors.get(column).enumValues() : null))
                .toList();
        index = new BitsetIndex(columnIndexes, bitToRule);

        inputStrategy = builder.inputStrategy;
        // readers return raw values; each column converts them (see ColumnIndex#maskFor)
        var names = evaluationOrder.toArray(String[]::new);
        if (inputStrategy == null) {
            Function<? super I, ?>[] byColumn = evaluationOrder.stream()
                    .map(column -> builder.extractors.get(column).extractor())
                    .toArray(Function[]::new);
            extractorReader = (input, column) -> byColumn[column].apply(input);
            mapReader = null;
        } else {
            mapReader = (values, column) -> values.get(names[column]);
            extractorReader = null;
        }

        int n = rules.size();
        outputByBit = new Object[n];
        @SuppressWarnings({"rawtypes", "unchecked"}) // a generic array cannot be created otherwise
        List<O>[] singles = new List[n];
        singleByBit = singles;
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

        report = new ValidationReport(TableAnalysis.analyse(index, rules, hitPolicy).stream()
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
     * @throws io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException if a value is
     *         outside its column's declared input values, or (map-based inputs) not a valid number or date
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
                if (!declared.contains(cell.getKey()) && !(CellParser.parse(cell.getValue(), ColumnType.STRING) instanceof UnaryTests.Any)) {
                    throw new InvalidDecisionTableException("Rule %s tests column '%s', which has no declared input"
                            .formatted(rule.priority(), cell.getKey()));
                }
            }
        }
    }

    /** Parsed cells per column, in rule order. */
    private static Map<String, List<UnaryTests>> parseCells(List<Praeceptum> rules, Map<String, ColumnType> types) {
        var cells = new LinkedHashMap<String, List<UnaryTests>>();
        types.forEach((column, type) -> cells.put(column, rules.stream().map(rule -> {
            try {
                return CellParser.parse(rule.input().values().get(column), type);
            } catch (CellSyntaxException e) {
                throw new InvalidDecisionTableException("Rule %s, column '%s': %s".formatted(rule.priority(), column, e.getMessage()));
            }
        }).toList()));
        return cells;
    }

    private static String quotedList(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static UnaryTests allowedTests(String column, ColumnType type, String declared) {
        if (declared == null) {
            return null;
        }
        UnaryTests tests;
        try {
            tests = CellParser.parse(declared, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException("Input values of column '%s': %s".formatted(column, e.getMessage()));
        }
        if (type == ColumnType.STRING && !(tests instanceof UnaryTests.Positive positive
                && positive.tests().stream().allMatch(UnaryTests.Equals.class::isInstance))) {
            throw new InvalidDecisionTableException("Input values of string column '%s' must be a list of values".formatted(column));
        }
        return tests;
    }

    /**
     * Columns with fewer wildcards first: evaluation stops as soon as no rule is left, so selective
     * columns first make that happen sooner. The result does not depend on the order.
     */
    private static List<String> orderByWildcards(List<String> columns, Map<String, List<UnaryTests>> cells) {
        var wildcards = new LinkedHashMap<String, Long>();
        for (var column : columns) {
            wildcards.put(column, cells.get(column).stream().filter(UnaryTests.Any.class::isInstance).count());
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
        /** @param enumValues for an enum-bound column, each constant's value by ordinal; else null */
        private record Binding<I>(ColumnType type, Function<? super I, ?> extractor, String[] enumValues) {
        }

        private HitPolicy hitPolicy;
        private AggregationOperator aggregation;
        private final Map<String, Binding<I>> extractors = new LinkedHashMap<>();
        private InputStrategy<I> inputStrategy;
        private final Map<String, ColumnType> columnTypes = new LinkedHashMap<>();
        private final Map<String, String> allowedValues = new LinkedHashMap<>();
        private OutputStrategy<O> outputStrategy;
        private final Map<String, List<String>> outputPriority = new LinkedHashMap<>();
        private List<Praeceptum> rules;
        private Severity failOn;
        /** Column types of a {@link #table(DecisionTable)}, checked against the bindings. */
        private Map<String, ColumnType> tableTypes;

        private Builder() {
        }

        /**
         * Takes the hit policy, aggregation, rules, input value lists and (for PRIORITY and
         * OUTPUT_ORDER) output value lists from a decision table, e.g. one read by {@code DmnReader}.
         * Inputs are still bound with {@code input(...)} and its typed variants, which must cover every
         * column of the table with its type, or with {@link #inputs(InputStrategy)}, in which case the
         * table's column types apply.
         */
        public Builder<I, O> table(DecisionTable table) {
            Objects.requireNonNull(table, "table");
            hitPolicy(table.hitPolicy());
            if (table.aggregation() != null) {
                aggregation(table.aggregation());
            }
            rules(table.rules());
            var types = new LinkedHashMap<String, ColumnType>();
            for (var input : table.inputs()) {
                types.put(input.name(), input.type());
                if (input.allowedValues() != null) {
                    allowedValues(input.name(), input.allowedValues());
                }
            }
            tableTypes = types;
            if (table.hitPolicy() == HitPolicy.PRIORITY || table.hitPolicy() == HitPolicy.OUTPUT_ORDER) {
                for (var output : table.outputs()) {
                    if (!output.values().isEmpty()) {
                        outputPriority(output.name(), output.values());
                    }
                }
            }
            return this;
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

        /** Binds a STRING column to a getter. Repeat for every column the rules test. Blank counts as missing. */
        public Builder<I, O> input(String column, Function<? super I, String> extractor) {
            return bind(column, ColumnType.STRING, extractor);
        }

        /** Binds a NUMBER column to a getter returning any {@link Number}; values compare as exact decimals. */
        public Builder<I, O> numberInput(String column, Function<? super I, ? extends Number> extractor) {
            return bind(column, ColumnType.NUMBER, extractor);
        }

        /** Binds a DATE column to a getter. */
        public Builder<I, O> dateInput(String column, Function<? super I, LocalDate> extractor) {
            return bind(column, ColumnType.DATE, extractor);
        }

        /**
         * Binds a STRING column to a getter returning an enum: the int-coded input path. Each constant
         * stands for the value {@code name()}; lookups index an array by ordinal instead of hashing.
         * The enum's values become the column's input value list unless one is declared, in which case
         * every constant must be in it.
         */
        public <E extends Enum<E>> Builder<I, O> enumInput(String column, Class<E> type, Function<? super I, E> extractor) {
            return enumInput(column, type, Enum::name, extractor);
        }

        /** {@link #enumInput(String, Class, Function)} where each constant stands for {@code value.apply(constant)}. */
        public <E extends Enum<E>> Builder<I, O> enumInput(String column, Class<E> type, Function<? super E, String> value,
                                                            Function<? super I, E> extractor) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            var values = Arrays.stream(type.getEnumConstants()).map(value).toArray(String[]::new);
            return bind(column, ColumnType.STRING, extractor, values);
        }

        private Builder<I, O> bind(String column, ColumnType type, Function<? super I, ?> extractor) {
            return bind(column, type, extractor, null);
        }

        private Builder<I, O> bind(String column, ColumnType type, Function<? super I, ?> extractor, String[] enumValues) {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(extractor, "extractor");
            if (extractors.putIfAbsent(column, new Binding<>(type, extractor, enumValues)) != null) {
                throw new IllegalArgumentException("Input column '%s' is declared twice".formatted(column));
            }
            return this;
        }

        /**
         * Converts each input to a map of column values (as text). Simpler than per-column binding, but
         * slower. Columns are STRING unless declared otherwise with {@link #columnType}.
         */
        public Builder<I, O> inputs(InputStrategy<I> inputStrategy) {
            this.inputStrategy = Objects.requireNonNull(inputStrategy, "inputStrategy");
            return this;
        }

        /** With {@link #inputs(InputStrategy)}: the type of a column, parsed from its text value. */
        public Builder<I, O> columnType(String column, ColumnType type) {
            columnTypes.put(Objects.requireNonNull(column, "column"), Objects.requireNonNull(type, "type"));
            return this;
        }

        /**
         * DMN input value list: the values a column accepts, written as unary tests of its type, e.g.
         * {@code "\"PT\",\"ES\""} or {@code "[0..150]"}. Evaluating any other (non-missing) value throws
         * {@code InvalidInputException}, and the validation report lists GAPs: declared values no rule
         * matches. String columns take a plain list of values.
         */
        public Builder<I, O> allowedValues(String column, String unaryTests) {
            allowedValues.put(Objects.requireNonNull(column, "column"), Objects.requireNonNull(unaryTests, "unaryTests"));
            return this;
        }

        /** {@link #allowedValues(String, String)} for a string column, from plain values. */
        public Builder<I, O> allowedValues(String column, List<String> values) {
            return allowedValues(column, quotedList(values));
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
            require(columnTypes.isEmpty() || inputStrategy != null,
                    "columnType(...) only applies with inputs(...); per-column bindings carry their type");
            if (tableTypes != null && inputStrategy == null) {
                tableTypes.forEach((column, type) -> {
                    var binding = extractors.get(column);
                    require(binding != null, "column '%s' of the table has no input binding".formatted(column));
                    require(binding.type() == type, "column '%s' is a %s column in the table but is bound as %s"
                            .formatted(column, type, binding.type()));
                });
            }
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
