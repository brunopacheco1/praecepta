/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
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
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
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
 * immutable; only a rule whose output names an input has its output converted again, into a new
 * instance, on every evaluation. Matching uses one bitmask per class of column values (the values the
 * rules cannot tell apart), so memory grows linearly with the table.
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

    private final OutputStrategy<O> outputStrategy;

    // Indexed by bit, i.e. by rule in the index's bit order (see bitToRule):
    /** The rule's converted output; null if it names an input (see namedInputsByBit). */
    private final Object[] outputByBit;
    /** The rule's output as a ready-made single-hit result, so that evaluation does not allocate one; null as above. */
    private final List<O>[] singleByBit;
    /** The rule's outputs as written, for ANY when outputs name inputs. */
    private final PraeceptaOutput[] ruleOutputByBit;
    /** The rule's outputs that name an input, filled in on every evaluation; null if it names none. */
    private final NamedInputs[] namedInputsByBit;
    /** Whether some rule's output names an input. */
    private final boolean namesInputs;
    /** The rule's cells that compare with another input, checked after the index; null if it has none. */
    private final VariableCell[][] variableCellsByBit;
    /** The rules with such cells. */
    private final long[] variableRules;
    private final boolean hasVariableCells;
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
        var cells = parseCells(rules, types, builder.feelCells);
        // cells that compare with another input are decided when evaluating; the index sees "any value"
        var variableCells = takeVariableCells(rules, types, cells, builder.inputStrategy != null);
        var allowedValues = allowedValues(builder, types.keySet());
        inputs = List.copyOf(types.keySet());
        var evaluationOrder = orderByWildcards(inputs, cells);

        int[] bitToRule = bitToRule(rules, builder.outputPriority);
        int words = BitsetIndex.wordsFor(rules.size());
        var enumValues = enumValuesByColumn(builder.bindings);
        var columnIndexes = evaluationOrder.stream()
                .map(column -> new ColumnIndex(column, types.get(column), inBitOrder(cells.get(column), bitToRule),
                        parseAllowedValues(column, types.get(column), allowedValues.get(column)), words, zoning(builder, column),
                        enumValues.get(column)))
                .toList();
        index = new BitsetIndex(columnIndexes, bitToRule);

        inputStrategy = builder.inputStrategy;
        // readers return raw values; each column converts them (see ColumnIndex#maskFor)
        extractorReader = inputStrategy == null ? extractorReader(builder.bindings, evaluationOrder) : null;
        mapReader = inputStrategy == null ? null : mapReader(evaluationOrder);

        outputStrategy = builder.outputStrategy;
        int ruleCount = rules.size();
        outputByBit = new Object[ruleCount];
        @SuppressWarnings({"rawtypes", "unchecked"}) // a generic array cannot be created otherwise
        List<O>[] singles = new List[ruleCount];
        singleByBit = singles;
        namedInputsByBit = new NamedInputs[ruleCount];
        ruleOutputByBit = new PraeceptaOutput[ruleCount];
        idByBit = new Integer[ruleCount];
        boolean anyNamed = false;
        for (int bit = 0; bit < ruleCount; bit++) {
            var rule = rules.get(bitToRule[bit]);
            idByBit[bit] = rule.priority();
            ruleOutputByBit[bit] = rule.output();
            if (rule.output().references().isEmpty()) {
                var output = outputStrategy.transform(rule.output());
                outputByBit[bit] = output;
                singleByBit[bit] = List.of(output);
            } else {
                namedInputsByBit[bit] = namedInputs(rule, evaluationOrder);
                anyNamed = true;
            }
        }
        namesInputs = anyNamed;
        // with outputs that name inputs, ANY compares the outputs of each evaluation instead
        sameOutputsByBit = hitPolicy == HitPolicy.ANY && !namesInputs ? sameOutputMasks(rules, bitToRule, index.words()) : null;
        reduce = reducer(builder.aggregation, builder.outputStrategy);
        count = builder.aggregation == AggregationOperator.COUNT ? builder.outputStrategy::count : null;
        noMatch = count == null ? List.of() : List.of(count.apply(0));
        variableCellsByBit = variableCellsByBit(variableCells, bitToRule, evaluationOrder);
        variableRules = variableRules(variableCellsByBit, words);
        hasVariableCells = !BitsetIndex.isEmpty(variableRules);
        report = validate(index, rules, hitPolicy, variableRules, builder.failOn);
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
    public List<O> evaluate(I input) {
        return inputStrategy == null
                ? evaluate(input, extractorReader)
                : evaluate(inputStrategy.transform(input).values(), mapReader);
    }

    /** Evaluates an input read column by column with {@code reader}, which also fills in outputs that name inputs. */
    @SuppressWarnings("unchecked")
    private <T> List<O> evaluate(T input, BitsetIndex.ColumnReader<T> reader) {
        long[] matches = index.match(input, reader);
        if (matches.length == 0 || hasVariableCells && !keepVariableMatches(matches, input, reader)) {
            return noMatch;
        }
        // the lowest set bit is the first matching rule in rule order, or the highest-priority one
        return switch (hitPolicy) {
            case FIRST, PRIORITY -> single(BitsetIndex.first(matches), input, reader);
            case UNIQUE -> {
                int bit = BitsetIndex.first(matches);
                if (BitsetIndex.next(matches, bit + 1) >= 0) {
                    throw violation(matches);
                }
                yield single(bit, input, reader);
            }
            case ANY -> {
                int bit = BitsetIndex.first(matches);
                if (namesInputs ? !sameOutputs(matches, input, reader) : !BitsetIndex.isSubset(matches, sameOutputsByBit[bit])) {
                    throw violation(matches);
                }
                yield single(bit, input, reader);
            }
            case RULE_ORDER, OUTPUT_ORDER, COLLECT -> {
                if (count != null) {
                    yield List.of(count.apply(BitsetIndex.count(matches)));
                }
                var selected = new Object[BitsetIndex.count(matches)];
                int i = 0;
                for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
                    selected[i++] = namedInputsByBit[bit] == null ? outputByBit[bit] : outputStrategy.transform(output(bit, input, reader));
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
     * columns named by input value lists and column types (in the order they were declared), then the
     * inputs that cells compare with and that are not columns (in the order first named).
     */
    public List<String> inputs() {
        return inputs;
    }

    /** Findings of the static analysis performed at build time. */
    public ValidationReport validationReport() {
        return report;
    }

    // ---- cells that compare with another input ----------------------------------------------

    /**
     * A cell that compares with another input, as in {@code > dateE}: the column it tests and, for each
     * input it names, that input's column; positions are in the evaluation order.
     */
    private record VariableCell(int column, UnaryTests cell, Map<String, Integer> names) {
    }

    /**
     * Takes the cells that name inputs out of {@code cells}, leaving "any value" for the index, and
     * returns them by rule (index in rule order) and column. Each named input becomes an input of the
     * engine: bound by its own getter, or with map-based inputs, read with the type of the column that
     * names it.
     *
     * @param types column types; named inputs that are not columns yet are added, so callers must read
     *              the inputs from it only after this call
     * @param cells cells by column; changed in place, and given an all-"any" column for each added input
     */
    private static Map<Integer, Map<String, UnaryTests>> takeVariableCells(List<Praeceptum> rules, Map<String, ColumnType> types,
            Map<String, List<UnaryTests>> cells, boolean mapInputs) {
        var byRule = new LinkedHashMap<Integer, Map<String, UnaryTests>>();
        var named = new LinkedHashMap<String, ColumnType>();
        for (var column : List.copyOf(cells.keySet())) {
            var columnCells = new ArrayList<>(cells.get(column));
            for (int r = 0; r < columnCells.size(); r++) {
                var cell = columnCells.get(r);
                if (!cell.namesInputs()) {
                    continue;
                }
                nameInputs(rules.get(r), column, cell, types, named, mapInputs);
                byRule.computeIfAbsent(r, any -> new LinkedHashMap<>()).put(column, cell);
                columnCells.set(r, new UnaryTests.Any());
            }
            cells.put(column, List.copyOf(columnCells));
        }
        named.forEach((name, type) -> {
            if (types.putIfAbsent(name, type) == null) {
                cells.put(name, Collections.nCopies(rules.size(), new UnaryTests.Any()));
            }
        });
        return byRule;
    }

    /**
     * Adds to {@code named} the inputs {@code cell} compares with, checked: each has an input binding
     * (or inputs are maps) and the type of {@code column}.
     */
    private static void nameInputs(Praeceptum rule, String column, UnaryTests cell, Map<String, ColumnType> types,
            Map<String, ColumnType> named, boolean mapInputs) {
        var type = types.get(column);
        for (var name : UnaryTests.names(cell)) {
            var known = types.containsKey(name) ? types.get(name) : named.get(name);
            if (known == null && !mapInputs) {
                throw new InvalidDecisionTableException("Rule %s, column '%s': compares with '%s', which has no input binding"
                        .formatted(rule.priority(), column, name));
            }
            if (known != null && known != type) {
                throw new InvalidDecisionTableException("Rule %s, column '%s': compares a %s with '%s', a %s"
                        .formatted(rule.priority(), column, type.feelName(), name, known.feelName()));
            }
            named.putIfAbsent(name, type);
        }
    }

    /** The variable cells by bit, with columns as positions in the evaluation order; null for rules without any. */
    private static VariableCell[][] variableCellsByBit(Map<Integer, Map<String, UnaryTests>> byRule, int[] bitToRule,
            List<String> evaluationOrder) {
        var byBit = new VariableCell[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            var ruleCells = byRule.get(bitToRule[bit]);
            if (ruleCells == null) {
                continue;
            }
            byBit[bit] = ruleCells.entrySet().stream()
                    .map(cell -> {
                        var names = new LinkedHashMap<String, Integer>();
                        UnaryTests.names(cell.getValue()).forEach(name -> names.put(name, evaluationOrder.indexOf(name)));
                        return new VariableCell(evaluationOrder.indexOf(cell.getKey()), cell.getValue(), Map.copyOf(names));
                    })
                    .toArray(VariableCell[]::new);
        }
        return byBit;
    }

    /** The rules with cells that compare with another input, as a mask. */
    private static long[] variableRules(VariableCell[][] variableCellsByBit, int words) {
        var mask = new long[words];
        for (int bit = 0; bit < variableCellsByBit.length; bit++) {
            if (variableCellsByBit[bit] != null) {
                BitsetIndex.set(mask, bit);
            }
        }
        return mask;
    }

    /**
     * Clears the matches whose cells that compare with other inputs fail for this input.
     *
     * @return whether a match is left
     */
    private <T> boolean keepVariableMatches(long[] matches, T input, BitsetIndex.ColumnReader<T> reader) {
        boolean any = false;
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            var cells = variableCellsByBit[bit];
            if (cells != null && !acceptsAll(cells, input, reader)) {
                BitsetIndex.clear(matches, bit);
            } else {
                any = true;
            }
        }
        return any;
    }

    private <T> boolean acceptsAll(VariableCell[] cells, T input, BitsetIndex.ColumnReader<T> reader) {
        for (var cell : cells) {
            var value = valueOf(cell.column(), input, reader);
            if (!VariableCells.accepts(cell.cell(), value, name -> valueOf(cell.names().get(name), input, reader))) {
                return false;
            }
        }
        return true;
    }

    private <T> Object valueOf(int column, T input, BitsetIndex.ColumnReader<T> reader) {
        return index.column(column).value(reader.read(input, column));
    }

    // ---- outputs that name an input --------------------------------------------------------

    /**
     * A rule's outputs that name an input: its other outputs, and for each named one, the output
     * column and the input column's position in the evaluation order. A class, not a record: it holds
     * arrays and is never compared.
     */
    private static final class NamedInputs {
        private final Map<String, String> values;
        private final String[] outputs;
        private final int[] columns;

        NamedInputs(Map<String, String> values, String[] outputs, int[] columns) {
            this.values = values;
            this.outputs = outputs;
            this.columns = columns;
        }
    }

    /**
     * The outputs of {@code rule} that name an input, checked: each names an input column, and none is
     * in an output priority list, where its place cannot be known before evaluation.
     */
    private static NamedInputs namedInputs(Praeceptum rule, List<String> evaluationOrder) {
        var references = rule.output().references();
        var outputs = new String[references.size()];
        var columns = new int[references.size()];
        int i = 0;
        for (var reference : references.entrySet()) {
            int column = evaluationOrder.indexOf(reference.getValue());
            if (column < 0) {
                throw new InvalidDecisionTableException("Rule %s: output '%s' names '%s', which is not an input column %s"
                        .formatted(rule.priority(), reference.getKey(), reference.getValue(), evaluationOrder));
            }
            outputs[i] = reference.getKey();
            columns[i++] = column;
        }
        return new NamedInputs(rule.output().values(), outputs, columns);
    }

    /** The single-hit result of the rule at {@code bit}: ready-made, unless it names inputs. */
    private <T> List<O> single(int bit, T input, BitsetIndex.ColumnReader<T> reader) {
        var ready = singleByBit[bit];
        return ready != null ? ready : List.of(outputStrategy.transform(output(bit, input, reader)));
    }

    /** The outputs of a rule that names inputs, filled in with the text of the input's values. */
    private <T> PraeceptaOutput output(int bit, T input, BitsetIndex.ColumnReader<T> reader) {
        var named = namedInputsByBit[bit];
        var values = new HashMap<>(named.values);
        for (int i = 0; i < named.outputs.length; i++) {
            var value = valueOf(named.columns[i], input, reader);
            values.put(named.outputs[i], value == null ? null : FeelLiterals.text(value));
        }
        return new PraeceptaOutput(values);
    }

    /** ANY, when outputs name inputs: whether every matching rule gives this input the same outputs. */
    private <T> boolean sameOutputs(long[] matches, T input, BitsetIndex.ColumnReader<T> reader) {
        PraeceptaOutput first = null;
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            var output = namedInputsByBit[bit] == null ? ruleOutputByBit[bit] : output(bit, input, reader);
            if (first == null) {
                first = output;
            } else if (!first.values().equals(output.values())) {
                return false;
            }
        }
        return true;
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
            var input = rule.output().references().get(output);
            if (input != null) {
                throw new InvalidDecisionTableException("Rule %s: output '%s' names input '%s', so it has no place in its priority list"
                        .formatted(rule.priority(), output, input));
            }
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

    /** Whether a column's inputs have a time zone: as its getter says, or either way when read from text. */
    private static Zoning zoning(Builder<?, ?> builder, String column) {
        return builder.inputStrategy != null ? Zoning.EITHER : builder.bindings.get(column).zoning();
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

    /** Parsed cells per column, in rule order: strict FEEL for a DMN table, else Praecepta's syntax. */
    private static Map<String, List<UnaryTests>> parseCells(List<Praeceptum> rules, Map<String, ColumnType> types, boolean feel) {
        var cells = new LinkedHashMap<String, List<UnaryTests>>();
        types.forEach((column, type) -> cells.put(column, rules.stream().map(rule -> parseCell(rule, column, type, feel)).toList()));
        return cells;
    }

    private static UnaryTests parseCell(Praeceptum rule, String column, ColumnType type, boolean feel) {
        try {
            var cell = rule.input().values().get(column);
            return feel ? CellParser.parseFeel(cell, type) : CellParser.parse(cell, type);
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
    private static ValidationReport validate(BitsetIndex index, List<Praeceptum> rules, HitPolicy hitPolicy, long[] variableRules,
            Severity failOn) {
        var report = new ValidationReport(TableAnalysis.analyse(index, rules, hitPolicy, variableRules).stream()
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
        /** Whether the rules' cells are strict FEEL, as a DMN table's are. */
        private boolean feelCells;

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
            feelCells = table.feelCells();
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

        /**
         * Binds a TIME column to a getter returning a time of day without a zone. Cells with an offset
         * ({@code time("08:30:00+01:00")}) cannot be compared with such times: they never match them.
         */
        public Builder<I, O> timeInput(String column, Function<? super I, LocalTime> extractor) {
            return bind(column, ColumnType.TIME, extractor);
        }

        /**
         * Binds a TIME column to a getter returning a time of day with an offset, compared by instant
         * ({@code 08:30+01:00} equals {@code 07:30Z}). Cells without a zone ({@code time("08:30:00")})
         * cannot be compared with such times: they never match them.
         */
        public Builder<I, O> offsetTimeInput(String column, Function<? super I, OffsetTime> extractor) {
            return bind(column, ColumnType.TIME, extractor, null, Zoning.ZONED);
        }

        /**
         * Binds a DATE_TIME column to a getter returning a date and time without a zone. Cells with a zone
         * ({@code date and time("2025-01-31T08:30:00Z")}) cannot be compared with such values: they never
         * match them.
         */
        public Builder<I, O> dateTimeInput(String column, Function<? super I, LocalDateTime> extractor) {
            return bind(column, ColumnType.DATE_TIME, extractor);
        }

        /**
         * Binds a DATE_TIME column to a getter returning a date and time with an offset or a zone id,
         * compared by instant. An {@code OffsetDateTime} binds through {@code OffsetDateTime::toZonedDateTime}.
         * Cells without a zone ({@code date and time("2025-01-31T08:30:00")}) cannot be compared with such
         * values: they never match them.
         */
        public Builder<I, O> zonedDateTimeInput(String column, Function<? super I, ZonedDateTime> extractor) {
            return bind(column, ColumnType.DATE_TIME, extractor, null, Zoning.ZONED);
        }

        /** Binds a DAY_TIME_DURATION column to a getter returning a duration, compared by length. */
        public Builder<I, O> durationInput(String column, Function<? super I, Duration> extractor) {
            return bind(column, ColumnType.DAY_TIME_DURATION, extractor);
        }

        /**
         * Binds a YEAR_MONTH_DURATION column to a getter returning a period of years and months, compared
         * by its total of months ({@code P1Y} equals {@code P12M}); a period with days is not a valid value.
         */
        public Builder<I, O> periodInput(String column, Function<? super I, Period> extractor) {
            return bind(column, ColumnType.YEAR_MONTH_DURATION, extractor);
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
            return bind(column, ColumnType.STRING, extractor, values, Zoning.LOCAL);
        }

        /**
         * Converts each input to a map of column values (as text). Simpler than per-column binding, but
         * slower. Columns are STRING unless declared otherwise with {@link #columnType}. Times and
         * date-times may be given with or without a time zone, so the validation report also looks for
         * gaps among both.
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
            return bind(column, type, extractor, null, Zoning.LOCAL);
        }

        private Builder<I, O> bind(String column, ColumnType type, Function<? super I, ?> extractor, List<String> enumValues,
                Zoning zoning) {
            Objects.requireNonNull(column, COLUMN);
            Objects.requireNonNull(extractor, "extractor");
            if (bindings.putIfAbsent(column, new Binding<>(type, extractor, enumValues, zoning)) != null) {
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

        /**
         * @param enumValues for an enum-bound column, each constant's value by ordinal; else null
         * @param zoning     whether the getter's times and date-times have a time zone
         */
        private record Binding<I>(ColumnType type, Function<? super I, ?> extractor, List<String> enumValues, Zoning zoning) {
        }
    }
}
