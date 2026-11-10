/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.lang.System.Logger.Level;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
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
    /** Reads a column with its bound getter; null with an {@link InputStrategy}. */
    private final BitsetIndex.ColumnReader<I> extractorReader;
    /** Reads a column from the input strategy's map; null with per-column bindings. */
    private final BitsetIndex.ColumnReader<Map<String, String>> mapReader;

    // Indexed by bit, i.e. by rule in the index's bit order (see bitToRule):
    /** The rule's converted output. */
    private final Object[] outputByBit;
    /** The rule's output as a ready-made single-hit result, so that evaluation does not allocate one. */
    private final List<O>[] singleByBit;
    /** The rule's identifier, for violation messages. */
    private final Integer[] idByBit;
    /** ANY only: the mask of the rules whose outputs equal this rule's. */
    private final long[][] sameOutputsByBit;

    /** The COLLECT aggregation combining outputs (SUM, MIN, MAX), or null. */
    private final BinaryOperator<O> reduce;
    /** COUNT only: the output for each number of matching rules; null otherwise. */
    private final IntFunction<O> count;
    /** The result when no rule matches: empty, or the COUNT output for zero. */
    private final List<O> noMatch;
    private final ValidationReport report;

    private PraeceptaEngine(Builder<I, O> builder) {
        hitPolicy = builder.hitPolicy;
        var rules = List.copyOf(builder.rules);

        var types = columnTypes(builder, rules);
        var allowedValues = allowedValues(builder, types.keySet());
        inputs = List.copyOf(types.keySet());
        var cells = parseCells(rules, types);
        var evaluationOrder = orderByWildcards(inputs, cells);

        int[] bitToRule = bitToRule(rules, builder.outputPriority);
        int words = BitsetIndex.wordsFor(rules.size());
        var enumValues = enumValuesByColumn(builder.bindings);
        var columnIndexes = evaluationOrder.stream()
                .map(column -> new ColumnIndex(column, types.get(column), inBitOrder(cells.get(column), bitToRule),
                        parseAllowedValues(column, types.get(column), allowedValues.get(column)), words, enumValues.get(column)))
                .toList();
        index = new BitsetIndex(columnIndexes, bitToRule);

        inputStrategy = builder.inputStrategy;
        // readers return raw values; each column converts them (see ColumnIndex#maskFor)
        extractorReader = inputStrategy == null ? extractorReader(builder.bindings, evaluationOrder) : null;
        mapReader = inputStrategy == null ? null : mapReader(evaluationOrder);

        int ruleCount = rules.size();
        outputByBit = new Object[ruleCount];
        @SuppressWarnings({"rawtypes", "unchecked"}) // a generic array cannot be created otherwise
        List<O>[] singles = new List[ruleCount];
        singleByBit = singles;
        idByBit = new Integer[ruleCount];
        for (int bit = 0; bit < ruleCount; bit++) {
            var rule = rules.get(bitToRule[bit]);
            var output = builder.outputStrategy.transform(rule.output());
            outputByBit[bit] = output;
            singleByBit[bit] = List.of(output);
            idByBit[bit] = rule.priority();
        }
        sameOutputsByBit = hitPolicy == HitPolicy.ANY ? sameOutputMasks(rules, bitToRule, index.words()) : null;
        reduce = reducer(builder.aggregation, builder.outputStrategy);
        count = builder.aggregation == AggregationOperator.COUNT ? builder.outputStrategy::count : null;
        noMatch = count == null ? List.of() : List.of(count.apply(0));
        report = validate(index, rules, hitPolicy, builder.failOn);
    }

    /** Starts configuring an engine. */
    public static <I, O> Builder<I, O> builder() {
        return new Builder<>();
    }

    /**
     * Evaluates the decision table.
     *
     * @return the outputs selected by the hit policy: at most one for single-hit policies (and for
     *         aggregations), several for multi-hit ones; empty if no rule matches, except for COUNT,
     *         which always returns one output
     * @throws HitPolicyViolationException if the input matches several rules under UNIQUE, or rules
     *                                     with different outputs under ANY
     * @throws InvalidInputException       if a value is outside its column's declared input values, or
     *                                     not a valid value of the column's type
     */
    @SuppressWarnings("unchecked")
    public List<O> evaluate(I input) {
        long[] matches = inputStrategy == null
                ? index.match(input, extractorReader)
                : index.match(inputStrategy.transform(input).values(), mapReader);
        if (matches.length == 0) {
            return noMatch;
        }
        // the lowest set bit is the first matching rule in rule order, or the highest-priority one
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
                var sameOutputs = sameOutputsByBit[bit];
                for (int word = 0; word < matches.length; word++) {
                    if ((matches[word] & ~sameOutputs[word]) != 0) {
                        throw violation(matches);
                    }
                }
                yield singleByBit[bit];
            }
            case RULE_ORDER, OUTPUT_ORDER, COLLECT -> {
                if (count != null) {
                    yield List.of(count.apply(BitsetIndex.count(matches)));
                }
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

    /** The hit policy. */
    public HitPolicy hitPolicy() {
        return hitPolicy;
    }

    /**
     * Input column names: the bound columns in the order they were bound or, with an {@link InputStrategy},
     * the table's columns, then the other columns the rules test (in alphabetical order), then any other
     * columns named by input value lists and column types (in the order they were declared).
     */
    public List<String> inputs() {
        return inputs;
    }

    /** Findings of the static analysis performed at build time. */
    public ValidationReport validationReport() {
        return report;
    }

    // ---- hit policies ----------------------------------------------------------------------

    /**
     * The rule at each bit. The lowest set bit wins, so bits follow rule order or, with output
     * priorities, priority order (ties keep rule order).
     */
    private static int[] bitToRule(List<Praeceptum> rules, Map<String, List<String>> outputPriority) {
        if (outputPriority.isEmpty()) {
            return IntStream.range(0, rules.size()).toArray();
        }
        var keys = rules.stream().map(rule -> priorityKey(rule, outputPriority)).toArray(int[][]::new);
        Comparator<int[]> lexicographic = Arrays::compare;
        return IntStream.range(0, rules.size()).boxed()
                .sorted(Comparator.comparing(rule -> keys[rule], lexicographic)) // stable: ties keep rule order
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /**
     * The position of each of the rule's outputs in its priority list, in the order the lists were
     * declared. Compared lexicographically, a smaller key is a higher priority.
     */
    private static int[] priorityKey(Praeceptum rule, Map<String, List<String>> outputPriority) {
        var key = new int[outputPriority.size()];
        int i = 0;
        for (var priorityList : outputPriority.entrySet()) {
            var output = priorityList.getKey();
            var value = rule.output().values().get(output);
            int position = priorityList.getValue().indexOf(value);
            if (position < 0) {
                throw new InvalidDecisionTableException("Rule %s: output %s=%s is not in its priority list %s"
                        .formatted(rule.priority(), output, value, priorityList.getValue()));
            }
            key[i++] = position;
        }
        return key;
    }

    /** Per bit, the mask of the rules with the same outputs as the bit's rule; equal outputs share one mask. */
    private static long[][] sameOutputMasks(List<Praeceptum> rules, int[] bitToRule, int words) {
        var maskByOutput = new HashMap<Object, long[]>();
        for (int bit = 0; bit < bitToRule.length; bit++) {
            BitsetIndex.set(maskByOutput.computeIfAbsent(rules.get(bitToRule[bit]).output(), output -> new long[words]), bit);
        }
        var masks = new long[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            masks[bit] = maskByOutput.get(rules.get(bitToRule[bit]).output());
        }
        return masks;
    }

    private static <O> BinaryOperator<O> reducer(AggregationOperator aggregation, OutputStrategy<O> strategy) {
        if (aggregation == null) {
            return null;
        }
        return switch (aggregation) {
            case SUM -> strategy::sum;
            case MIN -> strategy::min;
            case MAX -> strategy::max;
            case COUNT -> null;
        };
    }

    private HitPolicyViolationException violation(long[] matches) {
        var ids = new ArrayList<Integer>();
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            ids.add(idByBit[bit]);
        }
        return new HitPolicyViolationException(hitPolicy, ids);
    }

    // ---- columns -----------------------------------------------------------------------------

    /**
     * The type of each column. Bound columns carry their own, and rules may test no other column. With
     * an {@link InputStrategy}, the columns are the table's, those the rules test and those with input
     * values (STRING unless the table says otherwise), then {@code columnType(...)} overrides.
     */
    private static Map<String, ColumnType> columnTypes(Builder<?, ?> builder, List<Praeceptum> rules) {
        var types = new LinkedHashMap<String, ColumnType>();
        if (builder.inputStrategy == null) {
            builder.bindings.forEach((column, binding) -> types.put(column, binding.type()));
            rejectUndeclaredColumns(rules, types.keySet());
            return types;
        }
        if (builder.tableTypes != null) {
            types.putAll(builder.tableTypes);
        }
        columnsUsedBy(rules).forEach(column -> types.putIfAbsent(column, ColumnType.STRING));
        builder.allowedValues.keySet().forEach(column -> types.putIfAbsent(column, ColumnType.STRING));
        types.putAll(builder.columnTypes);
        return types;
    }

    /**
     * The input value list of each column: the declared ones and, for an enum-bound column without
     * one, its constants. An enum is a closed set of values.
     */
    private static Map<String, String> allowedValues(Builder<?, ?> builder, Set<String> columns) {
        var allowedValues = new LinkedHashMap<>(builder.allowedValues);
        builder.bindings.forEach((column, binding) -> {
            if (binding.enumValues() != null) {
                allowedValues.putIfAbsent(column, quotedList(binding.enumValues()));
            }
        });
        for (var column : allowedValues.keySet()) {
            if (!columns.contains(column)) {
                throw new InvalidDecisionTableException("Input values declared for unknown column '%s'".formatted(column));
            }
        }
        return allowedValues;
    }

    /** For each enum-bound column, each constant's value by ordinal. */
    private static Map<String, String[]> enumValuesByColumn(Map<String, ? extends Builder.Binding<?>> bindings) {
        var byColumn = new HashMap<String, String[]>();
        bindings.forEach((column, binding) -> {
            if (binding.enumValues() != null) {
                byColumn.put(column, binding.enumValues().toArray(String[]::new));
            }
        });
        return byColumn;
    }

    private static List<String> columnsUsedBy(List<Praeceptum> rules) {
        var columns = new TreeSet<String>();
        rules.forEach(rule -> columns.addAll(rule.input().values().keySet()));
        return List.copyOf(columns);
    }

    /** A rule may name a column that has no input binding only to leave it blank (a wildcard). */
    private static void rejectUndeclaredColumns(List<Praeceptum> rules, Set<String> declared) {
        for (var rule : rules) {
            for (var cell : rule.input().values().entrySet()) {
                var column = cell.getKey();
                if (!declared.contains(column) && !isWildcard(cell.getValue())) {
                    throw new InvalidDecisionTableException("Rule %s tests column '%s', which has no declared input"
                            .formatted(rule.priority(), column));
                }
            }
        }
    }

    /**
     * Whether a cell matches any value. The column has no type to parse the cell with, and a cell that is
     * not even valid as text (a comparison, an interval) still tests the column, so it is no wildcard.
     */
    private static boolean isWildcard(String cell) {
        try {
            return CellParser.parse(cell, ColumnType.STRING) instanceof UnaryTests.Any;
        } catch (CellSyntaxException e) {
            return false;
        }
    }

    /** Parsed cells per column, in rule order. */
    private static Map<String, List<UnaryTests>> parseCells(List<Praeceptum> rules, Map<String, ColumnType> types) {
        var cells = new LinkedHashMap<String, List<UnaryTests>>();
        types.forEach((column, type) -> cells.put(column, rules.stream().map(rule -> parseCell(rule, column, type)).toList()));
        return cells;
    }

    private static UnaryTests parseCell(Praeceptum rule, String column, ColumnType type) {
        try {
            return CellParser.parse(rule.input().values().get(column), type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException("Rule %s, column '%s': %s".formatted(rule.priority(), column, e.getMessage()));
        }
    }

    /** A column's cells, from rule order to bit order. */
    private static List<UnaryTests> inBitOrder(List<UnaryTests> cellsByRule, int[] bitToRule) {
        return Arrays.stream(bitToRule).mapToObj(cellsByRule::get).toList();
    }

    /** The values as a FEEL list of string literals, e.g. {@code "PT", "ES"}. */
    private static String quotedList(List<String> values) {
        return values.stream()
                .map(value -> FeelLiterals.literal(Objects.requireNonNull(value, "enum value")))
                .collect(Collectors.joining(", "));
    }

    /** A column's declared input values as unary tests of its type, or null if none are declared. */
    private static UnaryTests parseAllowedValues(String column, ColumnType type, String declared) {
        if (declared == null) {
            return null;
        }
        UnaryTests tests;
        try {
            tests = CellParser.parse(declared, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException("Input values of column '%s': %s".formatted(column, e.getMessage()));
        }
        if (type == ColumnType.STRING && tests.values().isEmpty()) {
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

    // ---- input readers -------------------------------------------------------------------------

    /** Reads a column, by its position in the evaluation order, with its bound getter. */
    @SuppressWarnings("unchecked") // a generic array cannot be created otherwise
    private static <I> BitsetIndex.ColumnReader<I> extractorReader(Map<String, Builder.Binding<I>> bindings,
                                                                   List<String> evaluationOrder) {
        Function<? super I, ?>[] byColumn = evaluationOrder.stream()
                .map(column -> bindings.get(column).extractor())
                .toArray(Function[]::new);
        return (input, column) -> byColumn[column].apply(input);
    }

    /** Reads a column, by its position in the evaluation order, from the input's map of values. */
    private static BitsetIndex.ColumnReader<Map<String, String>> mapReader(List<String> evaluationOrder) {
        var names = evaluationOrder.toArray(String[]::new);
        return (values, column) -> values.get(names[column]);
    }

    // ---- validation ----------------------------------------------------------------------------

    /**
     * Runs the static analysis, most severe findings first. Fails if a finding is at least as severe
     * as {@code failOn}; otherwise logs the findings as a warning.
     */
    private static ValidationReport validate(BitsetIndex index, List<Praeceptum> rules, HitPolicy hitPolicy, Severity failOn) {
        var report = new ValidationReport(TableAnalysis.analyse(index, rules, hitPolicy).stream()
                .sorted(Comparator.comparing(Issue::severity).reversed())
                .toList());
        if (failOn != null && report.hasIssuesAtLeast(failOn)) {
            throw new InvalidDecisionTableException(report);
        }
        if (!report.isEmpty()) {
            LOG.log(Level.WARNING, "Decision table has {0} validation issue(s):\n{1}", report.issues().size(), report);
        }
        return report;
    }

    // ---- builder -------------------------------------------------------------------------------

    /**
     * Configures and builds a {@link PraeceptaEngine}. Inputs are bound either per column with
     * {@link #input(String, Function)} (fastest) or all at once with {@link #inputs(InputStrategy)}.
     */
    public static final class Builder<I, O> {

        private static final String COLUMN = "column";

        private HitPolicy hitPolicy;
        private AggregationOperator aggregation;
        private List<Praeceptum> rules;
        private Severity failOn;

        private final Map<String, Binding<I>> bindings = new LinkedHashMap<>();
        private InputStrategy<I> inputStrategy;
        private final Map<String, ColumnType> columnTypes = new LinkedHashMap<>();
        private final Map<String, String> allowedValues = new LinkedHashMap<>();
        /** Column types of a {@link #table(DecisionTable)}, checked against the bindings. */
        private Map<String, ColumnType> tableTypes;

        private OutputStrategy<O> outputStrategy;
        private final Map<String, List<String>> outputPriority = new LinkedHashMap<>();

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
            if (usesOutputPriority(table.hitPolicy())) {
                for (var output : table.outputs()) {
                    if (!output.values().isEmpty()) {
                        outputPriority(output.name(), output.values());
                    }
                }
            }
            return this;
        }

        /** The hit policy (required). */
        public Builder<I, O> hitPolicy(HitPolicy hitPolicy) {
            this.hitPolicy = Objects.requireNonNull(hitPolicy, "hitPolicy");
            return this;
        }

        /** Aggregates COLLECT results into a single output, using the output strategy's sum/min/max/count. */
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

        /** Binds a BOOLEAN column to a getter; null counts as missing. */
        public Builder<I, O> booleanInput(String column, Function<? super I, Boolean> extractor) {
            return bind(column, ColumnType.BOOLEAN, extractor);
        }

        /** Binds a TIME column to a getter returning a time of day without a zone. */
        public Builder<I, O> timeInput(String column, Function<? super I, LocalTime> extractor) {
            return bind(column, ColumnType.TIME, extractor);
        }

        /** Binds a DATE_TIME column to a getter returning a date and time without a zone. */
        public Builder<I, O> dateTimeInput(String column, Function<? super I, LocalDateTime> extractor) {
            return bind(column, ColumnType.DATE_TIME, extractor);
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
            var values = Arrays.stream(type.getEnumConstants()).map(value).toList();
            return bind(column, ColumnType.STRING, extractor, values);
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
            columnTypes.put(Objects.requireNonNull(column, COLUMN), Objects.requireNonNull(type, "type"));
            return this;
        }

        /**
         * DMN input value list: the values a column accepts, written as unary tests of its type, e.g.
         * {@code "\"PT\",\"ES\""} or {@code "[0..150]"}. Evaluating any other (non-missing) value throws
         * {@code InvalidInputException}, and the validation report lists GAPs: declared values no rule
         * matches. String columns take a plain list of values.
         */
        public Builder<I, O> allowedValues(String column, String unaryTests) {
            allowedValues.put(Objects.requireNonNull(column, COLUMN), Objects.requireNonNull(unaryTests, "unaryTests"));
            return this;
        }

        /** {@link #allowedValues(String, String)} for a string column, from plain values. */
        public Builder<I, O> allowedValues(String column, List<String> values) {
            return allowedValues(column, quotedList(values));
        }

        /** Converts each rule's outputs to the output type (required). */
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

        /** The rules, in DMN rule order (required). */
        public Builder<I, O> rules(List<Praeceptum> rules) {
            this.rules = List.copyOf(rules);
            return this;
        }

        /** Fail {@link #build()} with an {@link InvalidDecisionTableException} if validation finds issues this severe. */
        public Builder<I, O> failOn(Severity severity) {
            this.failOn = Objects.requireNonNull(severity, "severity");
            return this;
        }

        /**
         * Parses the cells, indexes the rules and validates the table.
         *
         * @throws IllegalStateException         if a required setting is missing or two settings conflict
         * @throws InvalidDecisionTableException if the table is invalid, or validation finds an issue at
         *                                       the {@link #failOn} severity
         */
        public PraeceptaEngine<I, O> build() {
            require(hitPolicy != null, "a hit policy is required");
            require(outputStrategy != null, "an output strategy is required");
            require(rules != null, "rules are required");
            checkInputBindings();
            checkHitPolicyOptions();
            return new PraeceptaEngine<>(this);
        }

        private Builder<I, O> bind(String column, ColumnType type, Function<? super I, ?> extractor) {
            return bind(column, type, extractor, null);
        }

        private Builder<I, O> bind(String column, ColumnType type, Function<? super I, ?> extractor, List<String> enumValues) {
            Objects.requireNonNull(column, COLUMN);
            Objects.requireNonNull(extractor, "extractor");
            if (bindings.putIfAbsent(column, new Binding<>(type, extractor, enumValues)) != null) {
                throw new IllegalArgumentException("Input column '%s' is declared twice".formatted(column));
            }
            return this;
        }

        private void checkInputBindings() {
            require(bindings.isEmpty() != (inputStrategy == null),
                    "bind inputs either per column with input(...) or with inputs(...), not both and not neither");
            require(columnTypes.isEmpty() || inputStrategy != null,
                    "columnType(...) only applies with inputs(...); per-column bindings carry their type");
            if (tableTypes == null || inputStrategy != null) {
                return;
            }
            for (var tableColumn : tableTypes.entrySet()) {
                var column = tableColumn.getKey();
                var type = tableColumn.getValue();
                var binding = bindings.get(column);
                require(binding != null, "column '%s' of the table has no input binding".formatted(column));
                require(binding.type() == type, "column '%s' is a %s column in the table but is bound as %s"
                        .formatted(column, type, binding.type()));
            }
        }

        private void checkHitPolicyOptions() {
            require(aggregation == null || hitPolicy == HitPolicy.COLLECT, "aggregation is only allowed with the COLLECT hit policy");
            if (usesOutputPriority(hitPolicy)) {
                require(!outputPriority.isEmpty(), "%s requires outputPriority(...) lists".formatted(hitPolicy));
            } else {
                require(outputPriority.isEmpty(), "outputPriority(...) only applies to PRIORITY and OUTPUT_ORDER");
            }
        }

        private static boolean usesOutputPriority(HitPolicy hitPolicy) {
            return hitPolicy == HitPolicy.PRIORITY || hitPolicy == HitPolicy.OUTPUT_ORDER;
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }

        /** @param enumValues for an enum-bound column, each constant's value by ordinal; else null */
        private record Binding<I>(ColumnType type, Function<? super I, ?> extractor, List<String> enumValues) {
        }
    }
}
