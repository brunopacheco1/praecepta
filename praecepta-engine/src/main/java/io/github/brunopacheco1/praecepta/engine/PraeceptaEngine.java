/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
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
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * A decision table evaluated with strict DMN 1.5 hit-policy semantics.
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
 * List<Discount> discounts = engine.evaluate(order); // empty if no rule matches and there is no default output
 * }</pre>
 *
 * <p>Cells are DMN FEEL unary tests, parsed per column type: {@code "PT","ES"} (or unquoted
 * {@code PT,ES}), {@code not("FR")}, {@code != "FR"}, {@code >= 18}, {@code [18..65)},
 * {@code < date("2025-01-01")}, {@code < @"2025-01-01"}, {@code null}, and {@code -} or blank for any value.
 * See {@code CellParser}. A column bound with {@link Builder#listInput(String, Function)} holds lists, and its
 * cells hold when some element passes them.
 *
 * <p>The cells of a DMN table (strict FEEL) may be any FEEL unary tests (DMN 1.5, grammar rules 13 to 17; see
 * {@link FeelUnaryTests}): {@code > Limit * 2}, {@code [Low..Low + 10]}, {@code ? > 5 and ? < 10}, {@code [1, 2, 3]}.
 * The index evaluates the simple ones, which compare with literals; a rule with another one is matched by the index
 * as if that cell were {@code -}, and the cell is then applied to the input, as a cell that compares with another input
 * ({@code > limit}) is. The analysis treats such rules as it treats those: it never counts them as overlapping, hiding
 * other rules or covering a default output, and looks for no gaps in a table that has them. The columns of a DMN
 * table may be expressions ({@code Age * 12}), computed from the inputs they read, and its output entries any FEEL
 * expression ({@link PraeceptaOutput#expressions()}), parsed and typed when the engine is built; one whose value is
 * null whatever its inputs is a WARNING of the validation report ({@link ValidationReport.Kind#ALWAYS_NULL}). The
 * names such an expression reads that are not columns are inputs of the engine, as those a cell compares with are:
 * bound with their own getters, or with {@link Builder#inputs(InputStrategy)} read from text as values of the type
 * {@link Builder#columnType} declares, else of the column or output whose entry reads them first.
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
 * immutable; only a rule whose output names an input or is an expression
 * ({@link PraeceptaOutput#expressions()}, such as {@code Symptom + " is listed"}) has its output
 * computed and converted again, into a new instance, on every evaluation that selects it. Matching
 * uses one bitmask per class of column values (the values the
 * rules cannot tell apart), so memory grows linearly with the table; the mask of a class that only a
 * few rules accept, such as one product code among thousands, is stored compactly.
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
    /** {@link #extractorReader}, computing each computed column once per evaluation; null if none is computed. */
    private final BitsetIndex.ColumnReader<Computing<I>> computingExtractorReader;
    /** {@link #mapReader}, computing each computed column once per evaluation; null if none is computed. */
    private final BitsetIndex.ColumnReader<Computing<Map<String, String>>> computingMapReader;

    private final OutputStrategy<O> outputStrategy;

    // Indexed by bit, i.e. by rule in the index's bit order (see bitToRule):
    /** The rule's converted output; null if it reads an input (see computedByBit). */
    private final Object[] outputByBit;
    /** The rule's output as a ready-made single-hit result, so that evaluation does not allocate one; null as above. */
    private final List<O>[] singleByBit;
    /** The rule's outputs as written, for ANY when outputs read inputs. */
    private final PraeceptaOutput[] ruleOutputByBit;
    /** The rule's outputs that read inputs (name an input or are expressions), computed on every evaluation; null if it has none. */
    private final ComputedOutputs[] computedByBit;
    /** Whether some rule's output reads inputs. */
    private final boolean readsInputs;
    /** The outputs that hold numbers, which ANY compares by value. */
    private final Set<String> numericOutputs;
    /** The rule's cells that compare with another input, checked after the index; null if it has none. */
    private final VariableCell[][] variableCellsByBit;
    /** The rule's cells in the full FEEL language beyond the index, checked after it; null if it has none. */
    private final FeelCell[][] feelCellsByBit;
    /** The rules with either kind of cell. */
    private final long[] variableRules;
    private final boolean hasVariableCells;
    /**
     * Where the FEEL language reads each name its expressions read: the position of a column, in evaluation order, or
     * {@code -1 - i} for the {@code i}-th of the {@linkplain Builder#expressionInput values a graph gives}.
     */
    private final Map<String, Integer> nameReads;
    /** The values a graph gives, by {@link #nameReads}' index. */
    private final List<Function<Object, Object>> givenValues;
    /**
     * By column (evaluation order): how its value is computed from other inputs, or null for one read from the input;
     * empty if none is computed.
     */
    private final ComputedColumn[] computedColumns;
    /** The issues found in the rules' output entries (one always null, say), which the validation report lists. */
    private final List<Issue> outputIssues = new ArrayList<>();
    /** The rule's identifier, for violation messages. */
    private final Integer[] idByBit;
    /** ANY only: the mask of the rules whose outputs equal this rule's. */
    private final long[][] sameOutputsByBit;

    /** The COLLECT aggregation combining outputs (SUM, MIN, MAX), or null. */
    private final BinaryOperator<O> reduce;
    /**
     * SUM, MIN and MAX of a table with one output: that output, whose being missing in a matching rule makes
     * the aggregate missing (see {@link #aggregate}); null otherwise.
     */
    private final String aggregatedOutput;
    /** By bit, for a rule whose output reads no input: whether its {@link #aggregatedOutput} is missing. */
    private final boolean[] missingAggregateByBit;
    /** COUNT only: the output for each number of matching rules; null otherwise. */
    private final IntFunction<O> count;
    /** The result when no rule matches: the default output if there is one, else empty, whatever the aggregation. */
    private final List<O> noMatch;
    private final ValidationReport report;

    private PraeceptaEngine(Builder<I, O> builder) {
        hitPolicy = builder.hitPolicy;
        var rules = List.copyOf(builder.rules);

        var types = columnTypes(builder, rules);
        var lists = listInputs(builder);
        var expressions = new TableExpressions(scope(builder, types), builder.types, builder.parsed);
        var computed = computedColumns(builder, types, expressions);
        var cells = parseCells(rules, types, builder.feelCells, lists, expressions);
        for (int r = 0; r < rules.size(); r++) {
            expressions.outputs(rules.get(r), r, builder.tableOutputTypes);
        }
        // cells that compare with another input are decided when evaluating; the index sees "any value"
        var variableCells = takeVariableCells(rules, types, cells, builder.inputStrategy != null, lists);
        addExpressionInputs(expressions, types, cells, rules.size(), builder);
        var allowedValues = allowedValues(builder, types.keySet());
        inputs = List.copyOf(types.keySet());
        var evaluationOrder = orderByWildcards(inputs, cells, lists);
        givenValues = givenValues(builder);
        nameReads = nameReads(expressions, evaluationOrder, List.copyOf(builder.expressionInputs.keySet()), computed);
        // a table without FEEL beyond the index types nothing, and loads none of FEEL's types
        var nameTypes = expressions.isEmpty() ? Map.<String, FeelType>of() : nameTypes(types, lists, builder.expressionInputTypes);

        var numeric = numericOutputs(builder.tableOutputTypes, rules, types, expressions, nameTypes);
        int[] bitToRule = bitToRule(rules, builder.outputPriority, numeric);
        int words = BitsetIndex.wordsFor(rules.size());
        var enumValues = enumValuesByColumn(builder.bindings);
        var columnIndexes = evaluationOrder.stream()
                .map(column -> {
                    var type = types.get(column);
                    var columnCells = new ColumnIndex.Cells(inBitOrder(cells.get(column), bitToRule), words, builder.maskForm);
                    var allowed = parseAllowedValues(column, type, allowedValues.get(column));
                    return lists.contains(column)
                            ? ColumnIndex.ofLists(column, type, columnCells, allowed, zoning(builder, column))
                            : new ColumnIndex(column, type, columnCells, allowed, zoning(builder, column), enumValues.get(column));
                })
                .toList();
        index = new BitsetIndex(columnIndexes, bitToRule);

        inputStrategy = builder.inputStrategy;
        computedColumns = computedColumns(computed, evaluationOrder, types, lists);
        // readers return raw values; each column converts them (see ColumnIndex#maskFor)
        extractorReader = inputStrategy == null ? extractorReader(builder.bindings, evaluationOrder) : null;
        mapReader = inputStrategy == null ? null : mapReader(evaluationOrder);
        computingExtractorReader = extractorReader == null || computedColumns.length == 0 ? null : new ComputingReader<>(extractorReader);
        computingMapReader = mapReader == null || computedColumns.length == 0 ? null : new ComputingReader<>(mapReader);

        outputStrategy = builder.outputStrategy;
        int ruleCount = rules.size();
        outputByBit = new Object[ruleCount];
        @SuppressWarnings({"rawtypes", "unchecked"}) // a generic array cannot be created otherwise
        List<O>[] singles = new List[ruleCount];
        singleByBit = singles;
        computedByBit = new ComputedOutputs[ruleCount];
        ruleOutputByBit = new PraeceptaOutput[ruleCount];
        idByBit = new Integer[ruleCount];
        var outputs = new OutputContext(evaluationOrder, lists, types, expressions, nameTypes, builder);
        readsInputs = fillOutputsByBit(rules, bitToRule, outputs);
        numericOutputs = numeric;
        // with outputs that read inputs, ANY compares the outputs of each evaluation instead
        sameOutputsByBit = hitPolicy == HitPolicy.ANY && !readsInputs
                ? sameOutputMasks(rules, bitToRule, index.words(), builder.maskForm, numericOutputs)
                : null;
        reduce = reducer(builder.aggregation, builder.outputStrategy);
        aggregatedOutput = reduce == null ? null : onlyOutput(builder.tableOutputTypes, rules);
        missingAggregateByBit = missingAggregates(rules, bitToRule, aggregatedOutput, computedByBit);
        count = builder.aggregation == AggregationOperator.COUNT ? builder.outputStrategy::count : null;
        if (count != null) {
            count.apply(1); // so that a strategy without count fails here, not when a rule first matches
        }
        // DMN 1.5, 10.3.2.10, step 2: no match gives the default output, else null, before any aggregation
        noMatch = builder.defaultOutput == null ? List.of() : List.of(outputStrategy.transform(builder.defaultOutput));
        variableCellsByBit = variableCellsByBit(variableCells, bitToRule, evaluationOrder);
        feelCellsByBit = feelCellsByBit(expressions, bitToRule, evaluationOrder);
        variableRules = variableRules(variableCellsByBit, feelCellsByBit, words);
        hasVariableCells = !BitsetIndex.isEmpty(variableRules);
        // a rule that tests a computed column tests the columns it is computed from, which the index sees as unrelated
        var unanalysed = computedColumnRules(builder.feelCells ? cells.keySet() : computed.keySet(), cells, bitToRule, variableRules);
        var analysed = analyse(index, rules, hitPolicy, unanalysed, numericOutputs, builder.defaultOutput != null, outputIssues);
        report = enforce(analysed, builder.failOn, builder.logValidationReport);
    }

    /**
     * Fills in, by bit, each rule's id and output, and the output transformed once if it reads no input, or how to
     * compute it if it does.
     *
     * @return whether some rule's output reads inputs
     */
    private boolean fillOutputsByBit(List<Praeceptum> rules, int[] bitToRule, OutputContext context) {
        boolean anyReads = false;
        for (int bit = 0; bit < rules.size(); bit++) {
            var rule = rules.get(bitToRule[bit]);
            idByBit[bit] = rule.priority();
            ruleOutputByBit[bit] = rule.output();
            if (rule.output().readsInputs()) {
                computedByBit[bit] = computedOutputs(rule, bitToRule[bit], context);
                anyReads = true;
            } else {
                var output = outputStrategy.transform(rule.output());
                outputByBit[bit] = output;
                singleByBit[bit] = List.of(output);
            }
        }
        return anyReads;
    }

    /**
     * By bit, for a SUM, MIN or MAX of the {@code aggregatedOutput} (null for none), whether a rule whose output reads no
     * input leaves that output missing.
     */
    private static boolean[] missingAggregates(List<Praeceptum> rules, int[] bitToRule, String aggregatedOutput,
            ComputedOutputs[] computedByBit) {
        var missing = new boolean[rules.size()];
        for (int bit = 0; bit < rules.size(); bit++) {
            missing[bit] = aggregatedOutput != null && computedByBit[bit] == null
                    && rules.get(bitToRule[bit]).output().values().get(aggregatedOutput) == null;
        }
        return missing;
    }

    /** Starts configuring an engine. */
    public static <I, O> Builder<I, O> builder() {
        return new Builder<>();
    }

    /**
     * Evaluates the decision table.
     *
     * @return the outputs selected by the hit policy: at most one for single-hit policies (and for
     *         aggregations), several for multi-hit ones. If no rule matches: the default output alone, if
     *         there is one ({@link Builder#defaultOutput}), else empty, whatever the hit policy and
     *         aggregation (COUNT included: DMN 1.5, 10.3.2.10, gives null, not zero). A SUM, MIN or MAX
     *         of a table with one output is missing when that output is missing in a matching rule: the
     *         result is then that rule's output (see {@link OutputStrategy#transform})
     * @throws HitPolicyViolationException if the input matches several rules under UNIQUE, or rules
     *                                     with different outputs under ANY
     * @throws InvalidInputException       if a value is outside its column's declared input values, or
     *                                     not a valid value of the column's type (for a list, if an
     *                                     element is of another type)
     */
    public List<O> evaluate(I input) {
        return evaluate(input, null);
    }

    /**
     * Evaluates the decision table, as {@link #evaluate(Object)} does, telling {@code errors} of each error FEEL reports
     * while computing its outputs (DMN 1.5, 10.3.2.15); for {@link DecisionGraph}.
     *
     * @param errors receives each error's message; null to ignore them
     */
    List<O> evaluate(I input, Consumer<String> errors) {
        if (inputStrategy == null) {
            return computingExtractorReader == null
                    ? evaluate(input, extractorReader, errors)
                    : evaluate(new Computing<>(input, computedColumns.length), computingExtractorReader, errors);
        }
        var values = inputStrategy.transform(input).values();
        return computingMapReader == null
                ? evaluate(values, mapReader, errors)
                : evaluate(new Computing<>(values, computedColumns.length), computingMapReader, errors);
    }

    /** Whether a result of {@link #evaluate} is the default output: no rule matched (DMN 1.5, 10.3.2.10, step 2). */
    boolean isDefault(List<O> results) {
        return results == noMatch && !results.isEmpty();
    }

    /** Evaluates an input read column by column with {@code reader}, which also fills in outputs that name inputs. */
    @SuppressWarnings("unchecked")
    private <T> List<O> evaluate(T input, BitsetIndex.ColumnReader<T> reader, Consumer<String> errors) {
        long[] matches = index.match(input, reader);
        if (matches.length == 0 || hasVariableCells && !keepVariableMatches(matches, input, reader)) {
            return noMatch;
        }
        // the lowest set bit is the first matching rule in rule order, or the highest-priority one
        return switch (hitPolicy) {
            case FIRST, PRIORITY -> single(BitsetIndex.first(matches), input, reader, errors);
            case UNIQUE -> {
                int bit = BitsetIndex.first(matches);
                if (BitsetIndex.next(matches, bit + 1) >= 0) {
                    throw violation(matches);
                }
                yield single(bit, input, reader, errors);
            }
            case ANY -> {
                int bit = BitsetIndex.first(matches);
                if (readsInputs ? !sameOutputs(matches, input, reader) : !Masks.isSubset(matches, sameOutputsByBit[bit])) {
                    throw violation(matches);
                }
                yield single(bit, input, reader, errors);
            }
            case RULE_ORDER, OUTPUT_ORDER, COLLECT -> {
                if (count != null) {
                    yield List.of(count.apply(BitsetIndex.count(matches)));
                }
                if (reduce != null) {
                    yield aggregate(matches, input, reader, errors);
                }
                var selected = new Object[BitsetIndex.count(matches)];
                int i = 0;
                for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
                    selected[i++] = computedByBit[bit] == null ? outputByBit[bit] : transform(output(bit, input, reader, errors));
                }
                yield (List<O>) List.of(selected);
            }
        };
    }

    /**
     * SUM, MIN or MAX of the matching rules' outputs (one at least). FEEL's {@code sum}, {@code min} and
     * {@code max} give null for a list that holds null (DMN 1.5, 10.3.4.4 and 10.3.2.16), so where the table's
     * one output is missing in a matching rule, the aggregate is missing: the result is that rule's output, as
     * converted, and the strategy combines nothing.
     */
    @SuppressWarnings("unchecked")
    private <T> List<O> aggregate(long[] matches, T input, BitsetIndex.ColumnReader<T> reader, Consumer<String> errors) {
        O total = null;
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            O output;
            if (computedByBit[bit] == null) {
                if (missingAggregateByBit[bit]) {
                    return singleByBit[bit];
                }
                output = (O) outputByBit[bit];
            } else {
                var computed = output(bit, input, reader, errors);
                output = transform(computed);
                if (aggregatedOutput != null && computed.isNull(aggregatedOutput)) {
                    return List.of(output);
                }
            }
            total = total == null ? output : reduce.apply(total, output);
        }
        return List.of(total);
    }

    /** The hit policy. */
    public HitPolicy hitPolicy() {
        return hitPolicy;
    }

    /**
     * Input column names: the bound columns in the order they were bound or, with an {@link InputStrategy},
     * the table's columns, then the other columns the rules test (in alphabetical order), then any other
     * columns named by input value lists and column types (in the order they were declared), then the
     * inputs that cells compare with and that are not columns (in the order first named), then the other inputs the
     * table's FEEL (its cells beyond the simple unary tests, input expressions and output entries) reads (in the order
     * first read). A column whose input expression is an expression is among the table's columns, computed.
     */
    public List<String> inputs() {
        return inputs;
    }

    /**
     * Findings of the static analysis performed at build time; {@link Builder#build()} also logs them, unless
     * {@link Builder#logValidationReport(boolean)} turned that off.
     */
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
            Map<String, List<UnaryTests>> cells, boolean mapInputs, Set<String> lists) {
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
                rejectComparisonsWithLists(rules.get(r), column, cell, lists);
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

    /**
     * A cell may name an input that holds a list only as a whole test, {@code Flu Symptoms} or
     * {@code not(Flu Symptoms)}, which asks whether the value is in the list: FEEL compares no value with a
     * list, so {@code > limits}, {@code [low..limits]}, {@code = limits} or {@code != limits} would never hold
     * (an equality of a value and a list is null, DMN 1.5, Tables 52 and 53).
     */
    private static void rejectComparisonsWithLists(Praeceptum rule, String column, UnaryTests cell, Set<String> lists) {
        for (var test : cell.tests()) {
            if (test instanceof UnaryTests.Equals equals && !equals.explicit()) {
                continue; // a bare name: list contains(Flu Symptoms, ?), DMN 1.5, Table 55
            }
            for (var name : UnaryTests.names(test)) {
                if (lists.contains(name)) {
                    throw new InvalidDecisionTableException(("Rule %s, column '%s': compares with '%s', which holds lists; "
                            + "a cell can only ask whether the value is in it, as '%s' does").formatted(rule.priority(), column, name, name));
                }
            }
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

    /** The rules with cells that compare with another input, or in the full FEEL language, as a mask. */
    private static long[] variableRules(VariableCell[][] variableCellsByBit, FeelCell[][] feelCellsByBit, int words) {
        var mask = new long[words];
        for (int bit = 0; bit < variableCellsByBit.length; bit++) {
            if (variableCellsByBit[bit] != null || feelCellsByBit[bit] != null) {
                BitsetIndex.set(mask, bit);
            }
        }
        return mask;
    }

    /**
     * The rules the analysis leaves out ({@link TableAnalysis#analyse}): those with cells that compare with another input
     * or are in the full FEEL language ({@code variableRules}), and those that test a column computed from other inputs
     * ({@code Age * 12}), which the index sees as a column of its own, unrelated to the inputs it is computed from, so
     * that {@code < 18} on {@code Age} and {@code >= 216} on {@code Age * 12} would seem to overlap.
     *
     * @param columns the columns that may be computed: those whose input expressions are expressions, bound to a getter
     *                or not, in a strict FEEL table, else those the engine computes
     * @param cells   the cells by column, in rule order, those beyond the index {@code -}
     */
    private static long[] computedColumnRules(Set<String> columns, Map<String, List<UnaryTests>> cells, int[] bitToRule,
            long[] variableRules) {
        var computed = columns.stream().filter(TableExpressions::isExpression).toList();
        if (computed.isEmpty()) {
            return variableRules;
        }
        var mask = variableRules.clone();
        for (int bit = 0; bit < bitToRule.length; bit++) {
            int rule = bitToRule[bit];
            if (computed.stream().anyMatch(column -> !(cells.get(column).get(rule) instanceof UnaryTests.Any))) {
                BitsetIndex.set(mask, bit);
            }
        }
        return mask;
    }

    /**
     * Clears the matches whose cells that compare with other inputs, or are in the full FEEL language, fail for this
     * input.
     *
     * @return whether a match is left
     */
    private <T> boolean keepVariableMatches(long[] matches, T input, BitsetIndex.ColumnReader<T> reader) {
        boolean any = false;
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            var cells = variableCellsByBit[bit];
            var feel = feelCellsByBit[bit];
            if (cells != null && !acceptsAll(cells, input, reader) || feel != null && !acceptsAll(feel, input, reader)) {
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

    private <T> boolean acceptsAll(FeelCell[] cells, T input, BitsetIndex.ColumnReader<T> reader) {
        for (var cell : cells) {
            // a cell reports no errors: a test that is null is not satisfied, which is all a table asks of it
            if (!cell.tests().matches(valueOf(cell.column(), input, reader), name -> read(name, input, reader), null)) {
                return false;
            }
        }
        return true;
    }

    private <T> Object valueOf(int column, T input, BitsetIndex.ColumnReader<T> reader) {
        return index.column(column).value(reader.read(input, column));
    }

    /**
     * The value of a name the FEEL language reads: a column's, as its index reads it, or a value the graph gives; null
     * for a name the engine does not read, which is never asked for.
     */
    private <T> Object read(String name, T input, BitsetIndex.ColumnReader<T> reader) {
        var at = nameReads.get(name);
        if (at == null) {
            return null;
        }
        if (at >= 0) {
            return valueOf(at, input, reader);
        }
        // a graph gives its values from its own input, which an evaluation that computes columns wraps
        return givenValues.get(-1 - at).apply(input instanceof Computing<?> computing ? computing.input : input);
    }

    // ---- the full FEEL language: cells beyond the index and computed columns -----------------------

    /** A cell in the full FEEL language ({@link FeelUnaryTests}), applied to the value of its column, by position in evaluation order. */
    private record FeelCell(int column, FeelUnaryTests tests) {
    }

    /** The FEEL cells by bit, with columns as positions in the evaluation order; null for rules without any. */
    private static FeelCell[][] feelCellsByBit(TableExpressions expressions, int[] bitToRule, List<String> evaluationOrder) {
        var byBit = new FeelCell[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            var ruleCells = expressions.cells(bitToRule[bit]);
            if (!ruleCells.isEmpty()) {
                byBit[bit] = ruleCells.entrySet().stream()
                        .map(cell -> new FeelCell(evaluationOrder.indexOf(cell.getKey()), cell.getValue()))
                        .toArray(FeelCell[]::new);
            }
        }
        return byBit;
    }

    /**
     * A column whose input expression computes its value from other inputs ({@code Age * 12}): its value is the
     * expression's, which must be of the column's type (a value of another type is rejected, as a getter's is); for a
     * column of lists, a single value is a list of one (DMN 1.5, 10.3.2.9.4, "to singleton list").
     *
     * @param type the column's type, or a list of it for a column of lists
     */
    private record ComputedColumn(String name, FeelExpression expression, FeelType type) {

        /** The column's value, given the expression's. */
        Object value(Object computed) {
            var value = computed == null || computed instanceof Collection<?> || !(type instanceof FeelType.ListOf) ? computed : List.of(computed);
            return GraphValues.normalise(value, type, name);
        }
    }

    /**
     * The names in scope for the table's FEEL: its columns, the inputs bound to it, and the values a graph gives it.
     */
    private static Set<String> scope(Builder<?, ?> builder, Map<String, ColumnType> types) {
        var names = new LinkedHashSet<String>();
        // a column that is an expression is no name: its text would read as itself
        types.keySet().stream().filter(column -> !TableExpressions.isExpression(column)).forEach(names::add);
        names.addAll(builder.bindings.keySet());
        names.addAll(builder.expressionInputs.keySet());
        return names;
    }

    /**
     * Parses the columns whose input expressions compute their value, those that are expressions rather than names and
     * that no getter is bound to.
     *
     * @return their expressions, by column
     */
    private static Map<String, FeelExpression> computedColumns(Builder<?, ?> builder, Map<String, ColumnType> types,
            TableExpressions expressions) {
        for (var column : types.keySet()) {
            if (TableExpressions.isExpression(column) && !builder.bindings.containsKey(column)) {
                expressions.column(column, types.get(column));
            }
        }
        return expressions.columns();
    }

    /** The computed columns by position in evaluation order, null where a column is read; empty if none is computed. */
    private static ComputedColumn[] computedColumns(Map<String, FeelExpression> computed, List<String> evaluationOrder,
            Map<String, ColumnType> types, Set<String> lists) {
        if (computed.isEmpty()) {
            return new ComputedColumn[0];
        }
        var byColumn = new ComputedColumn[evaluationOrder.size()];
        computed.forEach((column, expression) -> {
            var type = lists.contains(column) ? FeelType.listOf(types.get(column)) : FeelType.of(types.get(column));
            byColumn[evaluationOrder.indexOf(column)] = new ComputedColumn(column, expression, type);
        });
        return byColumn;
    }

    /** Marks a computed column's value as not computed yet in {@link Computing#values}. */
    private static final Object NOT_COMPUTED = new Object();

    /**
     * An input being evaluated by a table that computes columns, with the values of those it has computed, so that each
     * is computed once per evaluation however often it is read: by the index, by FEEL cells and by output entries. A
     * class, not a record: it holds an array and is never compared.
     */
    private static final class Computing<T> {
        private final T input;
        /** By column (evaluation order): a computed column's value, or {@link #NOT_COMPUTED}. */
        private final Object[] values;

        Computing(T input, int columns) {
            this.input = input;
            this.values = new Object[columns];
            Arrays.fill(values, NOT_COMPUTED);
        }
    }

    /** Reads the columns {@code reader} reads, and computes the computed ones from them, once per evaluation. */
    private final class ComputingReader<T> implements BitsetIndex.ColumnReader<Computing<T>> {
        private final BitsetIndex.ColumnReader<T> reader;

        ComputingReader(BitsetIndex.ColumnReader<T> reader) {
            this.reader = reader;
        }

        @Override
        public Object read(Computing<T> computing, int column) {
            var computedColumn = computedColumns[column];
            if (computedColumn == null) {
                return reader.read(computing.input, column);
            }
            var value = computing.values[column];
            if (value == NOT_COMPUTED) {
                value = computedColumn.value(computedColumn.expression().evaluate(name -> PraeceptaEngine.this.read(name, computing, this)));
                computing.values[column] = value;
            }
            return value;
        }
    }

    /**
     * Adds the names the table's FEEL reads that are not columns, nor values a graph gives, as inputs of the engine, which
     * the index sees as columns of {@code -} cells: bound by their own getters, or with map-based inputs read with the
     * type {@code columnType(...)} declares, else that of the column or output whose entry reads them first.
     *
     * @throws InvalidDecisionTableException if one has no input binding, with per-column bindings
     */
    private static void addExpressionInputs(TableExpressions expressions, Map<String, ColumnType> types,
            Map<String, List<UnaryTests>> cells, int ruleCount, Builder<?, ?> builder) {
        expressions.named().forEach((name, type) -> {
            if (types.containsKey(name) || builder.expressionInputs.containsKey(name)) {
                return;
            }
            if (builder.inputStrategy == null) {
                throw new InvalidDecisionTableException("The table's expressions read '%s', which has no input binding".formatted(name));
            }
            types.put(name, type);
            cells.put(name, Collections.nCopies(ruleCount, new UnaryTests.Any()));
        });
    }

    /**
     * Where each name the table's FEEL reads is: a column, by position in evaluation order, or a value a graph gives.
     *
     * @throws InvalidDecisionTableException if a computed column reads another computed column, or a name is neither a
     *                                       column nor a value a graph gives
     */
    static Map<String, Integer> nameReads(TableExpressions expressions, List<String> evaluationOrder, List<String> given,
            Map<String, FeelExpression> computed) {
        var reads = new HashMap<String, Integer>();
        for (var name : expressions.names()) {
            int column = evaluationOrder.indexOf(name);
            int value = given.indexOf(name);
            if (column < 0 && value < 0) {
                throw new InvalidDecisionTableException(
                        "The table's expressions read '%s', which is neither an input of the table nor a value given to it".formatted(name));
            }
            reads.put(name, column >= 0 ? column : -1 - value);
        }
        computed.forEach((column, expression) -> {
            for (var name : expression.names()) {
                if (computed.containsKey(name)) {
                    throw new InvalidDecisionTableException("Input expression '%s' reads '%s', which is computed itself".formatted(column, name));
                }
            }
        });
        return Map.copyOf(reads);
    }

    /** The type of each name the table's FEEL may read: a column's, a list of it for a column of lists, or a given value's. */
    private static Map<String, FeelType> nameTypes(Map<String, ColumnType> types, Set<String> lists, Map<String, FeelType> given) {
        var nameTypes = new HashMap<>(given);
        types.forEach((name, type) -> nameTypes.put(name, lists.contains(name) ? FeelType.listOf(type) : FeelType.of(type)));
        return nameTypes;
    }

    // ---- outputs that read inputs: outputs that name an input, and expressions ---------------------

    /**
     * How an output's computed value is given (DMN 1.5, 10.3.2.9.4): as it is, for an output whose type the table does
     * not declare, each entry giving its own (10.3.2.10, step 3c); as its text, for a string output, which takes any
     * value as its text, as it takes unquoted literals; else bound to the output's type, as it is where it conforms,
     * a date as a date and time at UTC midnight, a list of one as its element, and as null where it does not conform.
     */
    private enum OutputBinding { AS_IS, TEXT, CONFORM }

    /**
     * One output of a rule that reads inputs: the output column, the expression that computes it, or, for an output that
     * names an input, that input's position in the evaluation order, and how its value is given.
     *
     * @param expression the expression, null for an output that names an input
     * @param column     the input it names, by position in the evaluation order; -1 for an expression
     * @param type       the output's type, for {@link OutputBinding#CONFORM}
     */
    private record Computed(String output, FeelExpression expression, int column, OutputBinding binding, FeelType type) {

        /** Whether the output names an input, so it takes that input's value as its text, a number keeping its scale. */
        boolean reference() {
            return expression == null;
        }
    }

    /**
     * A rule's outputs that read inputs: its other outputs, as written, and the computed ones. A class,
     * not a record: it holds an array and is never compared.
     */
    private static final class ComputedOutputs {
        private final Map<String, String> values;
        /** The outputs that read no input, as {@link ValueOutputs} takes them beside the values of the others. */
        private final PraeceptaOutput literals;
        private final Computed[] computed;

        ComputedOutputs(Map<String, String> values, Computed[] computed) {
            this.values = values;
            this.literals = new PraeceptaOutput(values);
            this.computed = computed;
        }
    }

    /** What computing the outputs of rules needs: where the inputs are, their types, and the table's FEEL. */
    private record OutputContext(List<String> evaluationOrder, Set<String> lists, Map<String, ColumnType> types,
            TableExpressions expressions, Map<String, FeelType> nameTypes, Map<String, ColumnType> outputTypes,
            Set<String> untypedOutputs) {

        OutputContext(List<String> evaluationOrder, Set<String> lists, Map<String, ColumnType> types, TableExpressions expressions,
                Map<String, FeelType> nameTypes, Builder<?, ?> builder) {
            this(evaluationOrder, lists, types, expressions, nameTypes, builder.tableOutputTypes, builder.untypedOutputs);
        }

        /** How an output's computed value is given: as it is if the table does not type it, as text if it is a string. */
        OutputBinding binding(String output) {
            if (untypedOutputs.contains(output)) {
                return OutputBinding.AS_IS;
            }
            var type = outputTypes.get(output);
            return type == null || type == ColumnType.STRING ? OutputBinding.TEXT : OutputBinding.CONFORM;
        }
    }

    /**
     * The outputs of {@code rule} that read inputs, checked: an output that names an input names one of the engine's
     * inputs that holds single values (the table's columns, or inputs its cells compare with), and none is in an output
     * priority list (see {@link #priorityKey}), where its place cannot be known before evaluation. An expression may read
     * any input, a list too ({@code Orders[1]}). An expression whose value is null
     * whatever its inputs, as one whose operands FEEL does not combine, or one of another type than its output, is
     * reported ({@link ValidationReport.Kind#ALWAYS_NULL}), and evaluated to null. {@code DmnReader} accepts the same names.
     */
    private ComputedOutputs computedOutputs(Praeceptum rule, int ruleIndex, OutputContext context) {
        var computed = new ArrayList<Computed>();
        rule.output().references().forEach((output, input) -> computed.add(new Computed(output, null,
                inputColumn(rule, output, input, context.evaluationOrder(), context.lists()), context.binding(output),
                outputType(context, output))));
        context.expressions().outputs(ruleIndex).forEach((output, expression) -> {
            var binding = context.binding(output);
            checkOutput(rule, output, expression, binding, context);
            computed.add(new Computed(output, expression, -1, binding, outputType(context, output)));
        });
        return new ComputedOutputs(rule.output().values(), computed.toArray(Computed[]::new));
    }

    private static FeelType outputType(OutputContext context, String output) {
        var type = context.outputTypes().get(output);
        return type == null ? FeelType.ANY : FeelType.of(type);
    }

    /**
     * Reports an output entry whose value is null whatever its inputs ({@link ValidationReport.Kind#ALWAYS_NULL}): its
     * expression is (operands FEEL does not combine, DMN 1.5, 10.3.2.15), or it gives values of a type that does not
     * conform to its output's (10.3.2.9.4).
     */
    private void checkOutput(Praeceptum rule, String output, FeelExpression expression, OutputBinding binding, OutputContext context) {
        var reasons = new ArrayList<String>();
        var type = expression.type(context.nameTypes()::get, reasons::add);
        var where = "rule %s, output '%s': '%s'".formatted(rule.priority(), output, expression.text().strip());
        if (type instanceof FeelType.Null) {
            var reason = reasons.isEmpty() ? "it is null" : reasons.getFirst();
            outputIssues.add(new Issue(ValidationReport.Kind.ALWAYS_NULL, List.of(rule.priority()),
                    "%s is null whatever its inputs: %s (DMN 1.5, 10.3.2.15)".formatted(where, reason), Map.of()));
        } else if (binding == OutputBinding.CONFORM && !mayConform(type, context.outputTypes().get(output))) {
            outputIssues.add(new Issue(ValidationReport.Kind.ALWAYS_NULL, List.of(rule.priority()),
                    "%s gives a %s, but the output is a %s, so its value is null (DMN 1.5, 10.3.2.9.4)".formatted(where, type.feelName(),
                            context.outputTypes().get(output).feelName()), Map.of()));
        }
    }

    /**
     * Whether a value of type {@code type} may be bound to an output of type {@code output} as something other than null
     * (DMN 1.5, 10.3.2.9.4): a value of that type, a date for a date and time, a list (of one), or one of any type.
     */
    private static boolean mayConform(FeelType type, ColumnType output) {
        return switch (type) {
            case FeelType.Simple(var simple) -> simple == output || simple == ColumnType.DATE && output == ColumnType.DATE_TIME;
            case FeelType.ListOf(var element) -> mayConform(element, output);
            case FeelType.Any any -> true;
            default -> false;
        };
    }

    /** The position, in the evaluation order, of the input an output reads, which must hold single values. */
    private static int inputColumn(Praeceptum rule, String output, String input, List<String> evaluationOrder, Set<String> lists) {
        int column = evaluationOrder.indexOf(input);
        if (column < 0) {
            throw new InvalidDecisionTableException("Rule %s: output '%s' names '%s', which is not an input column %s"
                    .formatted(rule.priority(), output, input, evaluationOrder));
        }
        if (lists.contains(input)) {
            throw new InvalidDecisionTableException("Rule %s: output '%s' names '%s', which holds lists; an output holds one value"
                    .formatted(rule.priority(), output, input));
        }
        return column;
    }

    /**
     * The outputs that hold numbers, which ANY compares by value ({@code 1.50} equals {@code 1.5}): those
     * a {@linkplain Builder#table table} declares as numbers and, for outputs the table does not declare,
     * those some rule computes as a number (naming a number input, or a number expression).
     */
    private static Set<String> numericOutputs(Map<String, ColumnType> declared, List<Praeceptum> rules, Map<String, ColumnType> types,
            TableExpressions expressions, Map<String, FeelType> nameTypes) {
        var numeric = new HashSet<String>();
        var number = FeelType.of(ColumnType.NUMBER);
        for (int r = 0; r < rules.size(); r++) {
            rules.get(r).output().references().forEach((output, input) -> {
                if (types.get(input) == ColumnType.NUMBER) {
                    numeric.add(output);
                }
            });
            expressions.outputs(r).forEach((output, expression) -> {
                if (number.equals(expression.type(nameTypes::get))) {
                    numeric.add(output);
                }
            });
        }
        declared.forEach((output, type) -> {
            if (type == ColumnType.NUMBER) {
                numeric.add(output);
            } else {
                numeric.remove(output);
            }
        });
        return Set.copyOf(numeric);
    }

    /** Whether two texts of an output are the same value: for a number output, the same number ({@code 1.50} is {@code 1.5}). */
    private static boolean sameValue(String a, String b, boolean number) {
        return number && a != null && b != null ? Objects.equals(normalisedNumber(a), normalisedNumber(b)) : Objects.equals(a, b);
    }

    /**
     * A rule's outputs as ANY compares them: as written, but each number output's value as a computed
     * number is written ({@link #numberText}), so that {@code 1.50} and {@code 1.5} are the same; text that
     * is not a number is kept.
     */
    static PraeceptaOutput comparable(PraeceptaOutput output, Set<String> numericOutputs) {
        if (numericOutputs.isEmpty()) {
            return output;
        }
        var values = new HashMap<>(output.values());
        values.replaceAll((column, text) -> numericOutputs.contains(column) && text != null ? normalisedNumber(text) : text);
        return new PraeceptaOutput(values, output.references(), output.expressions());
    }

    private static String normalisedNumber(String text) {
        try {
            return numberText(new BigDecimal(text.strip()));
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /** A number as a computed output is written: without trailing zeros or an exponent, and 0 as {@code 0}. */
    private static String numberText(BigDecimal number) {
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }

    /** The single-hit result of the rule at {@code bit}: ready-made, unless it reads inputs. */
    private <T> List<O> single(int bit, T input, BitsetIndex.ColumnReader<T> reader, Consumer<String> errors) {
        var ready = singleByBit[bit];
        return ready != null ? ready : List.of(transform(output(bit, input, reader, errors)));
    }

    /**
     * The outputs of a rule that reads inputs: the values of those it computes, and, written only when asked for, all of
     * them as text, which an output strategy other than {@link ValueOutputs} takes. A class, not a record: it holds an
     * array and is never compared.
     */
    private static final class Output {
        private final ComputedOutputs outputs;
        /** By computed output: its value, bound to its output. */
        private final Object[] values;
        private PraeceptaOutput text;

        Output(ComputedOutputs outputs, Object[] values) {
            this.outputs = outputs;
            this.values = values;
        }

        /**
         * The outputs as text, the computed ones filled in with the text of their values; a computed number has no
         * trailing zeros, since FEEL numbers are values. An output that names an input gives the input's value as
         * {@link FeelLiterals#text} writes it, a number keeping its scale, as a literal output keeps it.
         */
        PraeceptaOutput text() {
            if (text == null) {
                var texts = new HashMap<>(outputs.values);
                for (int i = 0; i < values.length; i++) {
                    var computed = outputs.computed[i];
                    texts.put(computed.output(), values[i] == null ? null : PraeceptaEngine.text(values[i], computed));
                }
                text = new PraeceptaOutput(texts);
            }
            return text;
        }

        /** The values of the computed outputs, by output; null for one that is null. */
        Map<String, Object> values() {
            var byOutput = new HashMap<String, Object>();
            for (int i = 0; i < values.length; i++) {
                byOutput.put(outputs.computed[i].output(), values[i]);
            }
            return byOutput;
        }

        /** Whether an output is missing: null as written, or computed to null. */
        boolean isNull(String output) {
            for (int i = 0; i < values.length; i++) {
                if (outputs.computed[i].output().equals(output)) {
                    return values[i] == null;
                }
            }
            return outputs.values.get(output) == null;
        }
    }

    /**
     * Converts computed outputs with the output strategy: their values, and the other outputs' text, for one that takes
     * values ({@link ValueOutputs}), which so never has them written as text; else all of them as text.
     */
    private O transform(Output output) {
        return outputStrategy instanceof ValueOutputs<O> values
                ? values.transform(output.outputs.literals, output.values())
                : outputStrategy.transform(output.text());
    }

    /** The outputs of a rule that reads inputs, computed: the value of the input each names, or of its expression. */
    private <T> Output output(int bit, T input, BitsetIndex.ColumnReader<T> reader, Consumer<String> errors) {
        var computedOutputs = computedByBit[bit];
        var values = new Object[computedOutputs.computed.length];
        for (int i = 0; i < values.length; i++) {
            var computed = computedOutputs.computed[i];
            var value = computed.reference()
                    ? valueOf(computed.column(), input, reader)
                    : computed.expression().evaluate(name -> read(name, input, reader), errors);
            values[i] = bound(value, computed, errors);
        }
        return new Output(computedOutputs, values);
    }

    /** A computed output's value as text: a computed number without trailing zeros, the value an output names as it is written. */
    private static String text(Object value, Computed computed) {
        return value instanceof BigDecimal number && !computed.reference() ? numberText(number) : outputText(value);
    }

    /** A computed output's value, given as its output takes it ({@link OutputBinding}). */
    private static Object bound(Object value, Computed computed, Consumer<String> errors) {
        if (value == null) {
            return null;
        }
        return switch (computed.binding()) {
            case AS_IS -> computed.reference() ? value : GraphValues.literalValue(value);
            case TEXT -> value instanceof String ? value : text(value, computed);
            case CONFORM -> {
                var conformed = GraphValues.conform(computed.reference() ? value : GraphValues.literalValue(value), computed.type(), 0);
                if (conformed == null && errors != null) {
                    errors.accept("output '%s': %s is not a %s, the output's type (DMN 1.5, 10.3.2.9.4)".formatted(computed.output(),
                            outputText(value), computed.type().feelName()));
                }
                yield conformed;
            }
        };
    }

    /** The deepest lists and contexts an output's text writes out; deeper ones are written {@code [...]} or <code>{...}</code>. */
    private static final int MAX_TEXT_DEPTH = 64;
    /**
     * The most characters an output's text writes of a list or a context; the rest is written {@code ...}. A value may
     * hold one list many times over ({@code {a: [1, 1], b: [a, a], c: [b, b]}.c}), which its text writes out each time,
     * so the text of a small value could be exponentially long.
     */
    static final int MAX_TEXT_LENGTH = 1 << 16;

    /**
     * The text of an output's value: a simple value's plain text ({@link FeelLiterals#text}), and a list, a context or a
     * range as FEEL writes it, its values as literals ({@code ["PT", "BR"]}, {@code {a: 1}}), as list inputs are read
     * from text; of a list or a context, at most {@value #MAX_TEXT_LENGTH} characters, then {@code ...}.
     */
    static String outputText(Object value) {
        if (!(value instanceof List<?>) && !(value instanceof Map<?, ?>)) {
            return FeelLiterals.text(value);
        }
        var text = new StringBuilder();
        if (!appendLiteral(text, value, 0)) {
            text.setLength(Math.min(text.length(), MAX_TEXT_LENGTH));
            text.append("...");
        }
        return text.toString();
    }

    /**
     * Writes a value as a literal. Each call writes a character at least before it calls itself, so the calls are no
     * more than the characters written, which {@link #MAX_TEXT_LENGTH} bounds, whatever the value's size.
     *
     * @return false if the text reached {@link #MAX_TEXT_LENGTH} characters before the value was written out
     */
    private static boolean appendLiteral(StringBuilder text, Object value, int depth) {
        if (text.length() >= MAX_TEXT_LENGTH) {
            return false;
        }
        if (value instanceof List<?> list) {
            if (depth >= MAX_TEXT_DEPTH) {
                text.append("[...]");
                return true;
            }
            text.append('[');
            var separator = "";
            for (var element : list) {
                text.append(separator);
                separator = ", ";
                if (!appendLiteral(text, element, depth + 1)) {
                    return false;
                }
            }
            text.append(']');
            return true;
        }
        if (value instanceof Map<?, ?> context) {
            return appendContext(text, context, depth);
        }
        text.append(value == null || isSimpleValue(value) ? FeelLiterals.literal(value) : value.toString());
        return true;
    }

    private static boolean appendContext(StringBuilder text, Map<?, ?> context, int depth) {
        if (depth >= MAX_TEXT_DEPTH) {
            text.append("{...}");
            return true;
        }
        text.append('{');
        var separator = "";
        for (var entry : context.entrySet()) {
            text.append(separator).append(FeelLiterals.literal(String.valueOf(entry.getKey()))).append(": ");
            separator = ", ";
            if (!appendLiteral(text, entry.getValue(), depth + 1)) {
                return false;
            }
        }
        text.append('}');
        return true;
    }

    /** Whether a value is one of a simple type, which {@link FeelLiterals#literal} writes. */
    private static boolean isSimpleValue(Object value) {
        return Arrays.stream(ColumnType.values()).anyMatch(type -> GraphValues.isValueOf(value, type));
    }

    /**
     * ANY, when outputs read inputs: whether every matching rule gives this input the same outputs, number
     * outputs compared by value.
     */
    private <T> boolean sameOutputs(long[] matches, T input, BitsetIndex.ColumnReader<T> reader) {
        Map<String, String> first = null;
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            var output = computedByBit[bit] == null ? ruleOutputByBit[bit] : output(bit, input, reader, null).text();
            var values = comparable(output, numericOutputs).values();
            if (first == null) {
                first = values;
            } else if (!first.equals(values)) {
                return false;
            }
        }
        return true;
    }

    // ---- hit policies ----------------------------------------------------------------------

    /**
     * The rule at each bit. The lowest set bit wins, so bits follow rule order or, with output
     * priorities, priority order (ties keep rule order).
     *
     * @param numericOutputs the outputs whose values are found in their priority lists by number
     */
    private static int[] bitToRule(List<Praeceptum> rules, Map<String, List<String>> outputPriority, Set<String> numericOutputs) {
        if (outputPriority.isEmpty()) {
            return IntStream.range(0, rules.size()).toArray();
        }
        var keys = rules.stream().map(rule -> priorityKey(rule, outputPriority, numericOutputs)).toArray(int[][]::new);
        Comparator<int[]> lexicographic = Arrays::compare;
        return IntStream.range(0, rules.size()).boxed()
                .sorted(Comparator.comparing(rule -> keys[rule], lexicographic)) // stable: ties keep rule order
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /**
     * The position of each of the rule's outputs in its priority list, in the order the lists were
     * declared; a number output's value is found by number ({@code 1.50} in a list that holds {@code 1.5}).
     * Compared lexicographically, a smaller key is a higher priority.
     */
    private static int[] priorityKey(Praeceptum rule, Map<String, List<String>> outputPriority, Set<String> numericOutputs) {
        var key = new int[outputPriority.size()];
        int i = 0;
        for (var priorityList : outputPriority.entrySet()) {
            var output = priorityList.getKey();
            var input = rule.output().references().get(output);
            if (input != null) {
                throw new InvalidDecisionTableException("Rule %s: output '%s' names input '%s', so it has no place in its priority list"
                        .formatted(rule.priority(), output, input));
            }
            var expression = rule.output().expressions().get(output);
            if (expression != null) {
                throw new InvalidDecisionTableException("Rule %s: output '%s' is the expression %s, so it has no place in its priority list"
                        .formatted(rule.priority(), output, expression));
            }
            var value = rule.output().values().get(output);
            var values = priorityList.getValue();
            boolean number = numericOutputs.contains(output);
            int position = IntStream.range(0, values.size()).filter(p -> sameValue(values.get(p), value, number)).findFirst().orElse(-1);
            if (position < 0) {
                throw new InvalidDecisionTableException("Rule %s: output %s=%s is not in its priority list %s"
                        .formatted(rule.priority(), output, value, priorityList.getValue()));
            }
            key[i++] = position;
        }
        return key;
    }

    /**
     * Per bit, the mask of the rules with the same outputs as the bit's rule (number outputs compared by
     * value); equal outputs share one mask, stored in its form ({@link Masks}): a rule whose outputs no
     * other rule has holds one bit.
     */
    private static long[][] sameOutputMasks(List<Praeceptum> rules, int[] bitToRule, int words, Masks.Form form,
            Set<String> numericOutputs) {
        var maskByOutput = new HashMap<PraeceptaOutput, long[]>();
        for (int bit = 0; bit < bitToRule.length; bit++) {
            var output = comparable(rules.get(bitToRule[bit]).output(), numericOutputs);
            BitsetIndex.set(maskByOutput.computeIfAbsent(output, any -> new long[words]), bit);
        }
        maskByOutput.replaceAll((output, mask) -> Masks.compact(mask, form));
        var masks = new long[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            masks[bit] = maskByOutput.get(comparable(rules.get(bitToRule[bit]).output(), numericOutputs));
        }
        return masks;
    }

    /**
     * The table's output, if it has exactly one: DMN aggregates a single output (8.2.11, "decision tables with
     * compound outputs do not support aggregation"). The outputs are those a {@linkplain Builder#table table}
     * declares, or else those the rules give; null if there are several, whose aggregation the strategy defines.
     */
    private static String onlyOutput(Map<String, ColumnType> declared, List<Praeceptum> rules) {
        var outputs = new HashSet<>(declared.keySet());
        if (outputs.isEmpty()) {
            for (var rule : rules) {
                outputs.addAll(rule.output().values().keySet());
                outputs.addAll(rule.output().references().keySet());
                outputs.addAll(rule.output().expressions().keySet());
            }
        }
        return outputs.size() == 1 ? outputs.iterator().next() : null;
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
     * values (STRING unless the table says otherwise), then {@code columnType(...)} overrides. A column of the
     * {@linkplain Builder#table table} whose input expression is an expression, not a name, is computed, so it needs no
     * binding.
     */
    private static Map<String, ColumnType> columnTypes(Builder<?, ?> builder, List<Praeceptum> rules) {
        var types = new LinkedHashMap<String, ColumnType>();
        if (builder.inputStrategy == null) {
            builder.bindings.forEach((column, binding) -> types.put(column, binding.type()));
            if (builder.tableTypes != null) {
                builder.tableTypes.forEach((column, type) -> {
                    if (TableExpressions.isExpression(column)) {
                        types.putIfAbsent(column, type);
                    }
                });
            }
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
     * The inputs that hold lists: those bound with {@code listInput(...)} or, with an {@link InputStrategy},
     * those the table or {@code listColumnType(...)} declare.
     */
    private static Set<String> listInputs(Builder<?, ?> builder) {
        if (builder.inputStrategy != null) {
            return Set.copyOf(builder.listColumns);
        }
        var lists = new HashSet<String>();
        builder.bindings.forEach((column, binding) -> {
            if (binding.list()) {
                lists.add(column);
            }
        });
        return lists;
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

    /**
     * Whether a column's inputs have a time zone: as its getter says, or either way when read from text or computed by
     * its input expression.
     */
    private static Zoning zoning(Builder<?, ?> builder, String column) {
        var binding = builder.bindings.get(column);
        return builder.inputStrategy != null || binding == null ? Zoning.EITHER : binding.zoning();
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

    /**
     * Parsed cells per column, in rule order: strict FEEL for a DMN table (a list column's with
     * {@code list contains(?, ...)}), else Praecepta's syntax. A strict FEEL cell beyond the simple unary tests the index
     * evaluates is {@code -} here, and kept in {@code expressions}, which apply it once the index has matched its rule.
     */
    private static Map<String, List<UnaryTests>> parseCells(List<Praeceptum> rules, Map<String, ColumnType> types, boolean feel,
            Set<String> lists, TableExpressions expressions) {
        var cells = new LinkedHashMap<String, List<UnaryTests>>();
        types.forEach((column, type) -> {
            var columnCells = new ArrayList<UnaryTests>(rules.size());
            for (int r = 0; r < rules.size(); r++) {
                var rule = rules.get(r);
                try {
                    columnCells.add(parseCell(rule, column, type, feel, lists.contains(column)));
                } catch (CellSyntaxException e) {
                    if (!feel) {
                        throw new InvalidDecisionTableException("Rule %s, column '%s': %s".formatted(rule.priority(), column, e.getMessage()));
                    }
                    expressions.cell(rule, r, column, type, e);
                    columnCells.add(new UnaryTests.Any());
                }
            }
            cells.put(column, Collections.unmodifiableList(columnCells));
        });
        return cells;
    }

    /** A cell as the index reads it. */
    private static UnaryTests parseCell(Praeceptum rule, String column, ColumnType type, boolean feel, boolean list) {
        var cell = rule.input().values().get(column);
        if (!feel) {
            var parsed = CellParser.parse(cell, type);
            if (list && parsed.tests().stream().anyMatch(test -> test instanceof UnaryTests.NotEqual(UnaryTests.NullTest n))) {
                throw new InvalidDecisionTableException(("Rule %s, column '%s': '!= null' is not a test of a list column, "
                        + "where 'null' tests the list and other tests its elements; write not(null) for a list that is there")
                        .formatted(rule.priority(), column));
            }
            return parsed;
        }
        return list ? CellParser.parseFeelList(cell, type) : CellParser.parseFeel(cell, type);
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
     * columns first make that happen sooner, and once a sparse column has confined the candidates to a
     * few words, later columns only intersect those. Columns that hold lists go after the others with as
     * many wildcards, since a list's mask is built whole, element by element. The result does not depend
     * on the order.
     */
    private static List<String> orderByWildcards(List<String> columns, Map<String, List<UnaryTests>> cells, Set<String> lists) {
        var wildcards = new LinkedHashMap<String, Long>();
        for (var column : columns) {
            wildcards.put(column, cells.get(column).stream().filter(UnaryTests.Any.class::isInstance).count());
        }
        Comparator<Map.Entry<String, Long>> byWildcards = Map.Entry.comparingByValue();
        return wildcards.entrySet().stream()
                .sorted(byWildcards.thenComparing(column -> lists.contains(column.getKey()))) // stable: ties keep declaration order
                .map(Map.Entry::getKey)
                .toList();
    }

    // ---- input readers -------------------------------------------------------------------------

    /**
     * Reads a column, by its position in the evaluation order, with its bound getter; a computed column, which has none,
     * is never read with it ({@link #computing}).
     */
    @SuppressWarnings("unchecked") // a generic array cannot be created otherwise
    private static <I> BitsetIndex.ColumnReader<I> extractorReader(Map<String, Builder.Binding<I>> bindings,
                                                                   List<String> evaluationOrder) {
        Function<? super I, ?> computed = input -> null;
        Function<? super I, ?>[] byColumn = evaluationOrder.stream()
                .map(column -> bindings.containsKey(column) ? bindings.get(column).extractor() : computed)
                .toArray(Function[]::new);
        return (input, column) -> byColumn[column].apply(input);
    }

    /** The getters of the values a graph gives the table's FEEL ({@link Builder#expressionInput}), in the order bound. */
    @SuppressWarnings("unchecked") // each is applied to the engine's own input
    private static List<Function<Object, Object>> givenValues(Builder<?, ?> builder) {
        var getters = new ArrayList<Function<Object, Object>>();
        builder.expressionInputs.values().forEach(getter -> getters.add((Function<Object, Object>) getter));
        return List.copyOf(getters);
    }

    /** Reads a column, by its position in the evaluation order, from the input's map of values. */
    private static BitsetIndex.ColumnReader<Map<String, String>> mapReader(List<String> evaluationOrder) {
        var names = evaluationOrder.toArray(String[]::new);
        return (values, column) -> values.get(names[column]);
    }

    // ---- validation ----------------------------------------------------------------------------

    /** Runs the static analysis: its findings, and those of the output entries, most severe first. */
    private static ValidationReport analyse(BitsetIndex index, List<Praeceptum> rules, HitPolicy hitPolicy, long[] variableRules,
            Set<String> numericOutputs, boolean hasDefaultOutput, List<Issue> outputIssues) {
        var analysed = new ArrayList<>(
                TableAnalysis.analyse(index, rules, hitPolicy, variableRules, output -> comparable(output, numericOutputs)));
        analysed.addAll(outputIssues);
        if (hasDefaultOutput) {
            // DMN 1.5, 10.3.2.10: "A complete decision table SHALL NOT specify a default output value"
            var seen = index.allRules();
            BitsetIndex.andNot(seen, variableRules);
            if (Completeness.isComplete(index, seen)) {
                analysed.add(new Issue(ValidationReport.Kind.UNREACHABLE_DEFAULT_OUTPUT, List.of()));
            }
        }
        return new ValidationReport(analysed.stream()
                .sorted(Comparator.comparing(Issue::severity).reversed())
                .toList());
    }

    /**
     * Fails if a finding of the report is at least as severe as {@code failOn}; otherwise logs the findings as a
     * warning, unless {@code log} is false.
     */
    private static ValidationReport enforce(ValidationReport report, Severity failOn, boolean log) {
        if (failOn != null && report.hasIssuesAtLeast(failOn)) {
            throw new InvalidDecisionTableException(report);
        }
        if (log && !report.isEmpty()) {
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
        private boolean logValidationReport = true;
        private Masks.Form maskForm = Masks.Form.AUTO;
        /** The output when no rule matches, or null. */
        private PraeceptaOutput defaultOutput;

        private final Map<String, Binding<I>> bindings = new LinkedHashMap<>();
        private InputStrategy<I> inputStrategy;
        private final Map<String, ColumnType> columnTypes = new LinkedHashMap<>();
        private final Map<String, String> allowedValues = new LinkedHashMap<>();
        /** Column types of a {@link #table(DecisionTable)}, checked against the bindings. */
        private Map<String, ColumnType> tableTypes;
        /** The inputs a {@link #table(DecisionTable)} declares as lists, checked against the bindings. */
        private Set<String> tableLists = Set.of();
        /** The {@link #table(DecisionTable)}, whose cells name the inputs they compare with. */
        private DecisionTable table;
        /**
         * The inputs a {@link #table(DecisionTable)}'s cells compare with; null until a binding that is not
         * one of its columns asks, since finding them parses every cell.
         */
        private Set<String> tableNamedInputs;
        /** Output column types of a {@link #table(DecisionTable)}: its number outputs are compared by value. */
        private Map<String, ColumnType> tableOutputTypes = Map.of();
        /** With {@link #inputs(InputStrategy)}: the inputs that hold lists. */
        private final Set<String> listColumns = new LinkedHashSet<>();
        /** Whether the rules' cells are strict FEEL, as a DMN table's are. */
        private boolean feelCells;
        /** The outputs a {@link #table(DecisionTable)} gives no type, whose computed values keep their own. */
        private Set<String> untypedOutputs = Set.of();
        /** For {@link DecisionGraph}: the getters of the values the table's FEEL reads beside its columns, by name. */
        private final Map<String, Function<? super I, ?>> expressionInputs = new LinkedHashMap<>();
        /** Their types. */
        private final Map<String, FeelType> expressionInputTypes = new HashMap<>();
        /** For {@link DecisionGraph}: the types its model defines (item definitions), which the table's FEEL may name. */
        private Map<String, FeelType> types = Map.of();
        /** For {@link DecisionGraph}: the table's FEEL as its analysis parsed it, and the scope it parsed it in. */
        private TableExpressions.Parsed parsed = TableExpressions.Parsed.NONE;

        private OutputStrategy<O> outputStrategy;
        private final Map<String, List<String>> outputPriority = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Takes the hit policy, aggregation, rules, input value lists, (for PRIORITY and OUTPUT_ORDER) output
         * value lists and default output ({@link #defaultOutput}) from a decision table, e.g. one read by
         * {@code DmnReader}.
         * Inputs are still bound with {@code input(...)} and its typed variants, which must cover every
         * column of the table with its type ({@code listInput(...)} for the table's list inputs), or with
         * {@link #inputs(InputStrategy)}, in which case the table's column types and list inputs apply.
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
            tableLists = table.listInputs();
            this.table = table;
            tableNamedInputs = null;
            var outputTypes = new LinkedHashMap<String, ColumnType>();
            table.outputs().forEach(output -> outputTypes.put(output.name(), output.type()));
            tableOutputTypes = outputTypes;
            untypedOutputs = table.outputs().stream().filter(output -> !output.typed()).map(DecisionTable.OutputColumn::name)
                    .collect(Collectors.toUnmodifiableSet());
            listColumns.addAll(table.listInputs());
            feelCells = table.feelCells();
            table.defaultOutput().ifPresent(this::defaultOutput);
            if (usesOutputPriority(table.hitPolicy())) {
                for (var output : table.outputs()) {
                    if (!output.values().isEmpty()) {
                        outputPriority(output.name(), output.values());
                    }
                }
            }
            return this;
        }

        /**
         * Whether an input holds single values in the {@link #table(DecisionTable)}: it is one of its
         * columns, or an input its cells compare with, and not one of its lists.
         */
        private boolean tableHoldsSingles(String input) {
            if (tableLists.contains(input)) {
                return false;
            }
            if (tableTypes.containsKey(input)) {
                return true;
            }
            if (tableNamedInputs == null) {
                tableNamedInputs = namedInputs(table);
            }
            return tableNamedInputs.contains(input);
        }

        /** The inputs a table's cells compare with; none if a cell is invalid, which {@link #build()} reports. */
        private static Set<String> namedInputs(DecisionTable table) {
            try {
                return table.namedInputs().keySet();
            } catch (IllegalArgumentException e) {
                return Set.of();
            }
        }

        /** The hit policy (required). */
        public Builder<I, O> hitPolicy(HitPolicy hitPolicy) {
            this.hitPolicy = Objects.requireNonNull(hitPolicy, "hitPolicy");
            return this;
        }

        /**
         * The output when no rule matches: DMN's default output values (DMN 1.5, 10.3.2.10: "if no rules match,
         * if a default output value d is specified, DTI=FEEL(d)"). Evaluation then gives this output instead of
         * none, whatever the hit policy: alone under a single-hit policy, as a list of one under a multi-hit
         * policy, and as the result of a COLLECT aggregation (COUNT included), not aggregated. It is converted
         * once, when the engine is built, like a rule's output. Its values are plain text, as a rule's are, and
         * null for an output without a default. {@link #table} takes it from the table's output columns
         * ({@link DecisionTable#defaultOutput}). When every input, a missing value in any column included, matches a
         * rule, the default can never be given, and the validation report has the ERROR
         * {@link ValidationReport.Kind#UNREACHABLE_DEFAULT_OUTPUT} ("A complete decision table SHALL NOT specify a
         * default output value").
         *
         * @throws IllegalArgumentException if the output names an input or is an expression: a default is a value
         */
        public Builder<I, O> defaultOutput(PraeceptaOutput output) {
            Objects.requireNonNull(output, "output");
            if (output.readsInputs()) {
                throw new IllegalArgumentException("A default output is a value; it cannot name an input or be an expression: " + output);
            }
            this.defaultOutput = output;
            return this;
        }

        /**
         * Aggregates COLLECT results into a single output, using the output strategy's sum/min/max/count. When
         * no rule matches, there is nothing to aggregate: the result is the default output ({@link #defaultOutput}),
         * or none, COUNT included (DMN 1.5, 10.3.2.10, step 2). SUM, MIN and MAX of a table with one output are
         * missing when that output is missing in a matching rule, as FEEL's {@code sum}, {@code min} and {@code max}
         * are null for a list that holds null (10.3.4.4); COUNT counts every matching rule.
         */
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
         * Binds a TIME column to a getter returning a time of day without a zone. A cell with an offset
         * ({@code time("08:30:00+01:00")}) is compared with such a time by its local time, as FEEL does when only
         * one of the two has an offset: it matches {@code 08:30}.
         */
        public Builder<I, O> timeInput(String column, Function<? super I, LocalTime> extractor) {
            return bind(column, ColumnType.TIME, extractor);
        }

        /**
         * Binds a TIME column to a getter returning a time of day with an offset, compared by instant
         * ({@code 08:30+01:00} equals {@code 07:30Z}). A cell without a zone ({@code time("08:30:00")}) is
         * compared with the local time, so it matches {@code 08:30+01:00} and {@code 08:30Z}.
         */
        public Builder<I, O> offsetTimeInput(String column, Function<? super I, OffsetTime> extractor) {
            return bind(column, ColumnType.TIME, extractor, null, Zoning.ZONED, false);
        }

        /**
         * Binds a TIME column to a getter returning a time of day with a zone id ({@code 08:30@Europe/Paris}). It
         * compares with a cell's time in the same zone by local time; DMN 1.5 does not define how it compares with
         * a time in another zone, at an offset or without a zone, so such a test is unknown: neither it nor its
         * {@code not(...)} matches. A time in a zone with one fixed offset ({@code 08:30@Etc/UTC},
         * {@code 08:30@Etc/GMT-2}) is the time at that offset (DMN 1.5, Table 72): it matches as
         * {@link #offsetTimeInput} matches {@code 08:30Z}, so {@code time("08:30:00Z")}, {@code <= time("09:30:00+01:00")}
         * and {@code time("08:30:00@Etc/UTC")} all hold for it.
         */
        public Builder<I, O> zonedTimeInput(String column, Function<? super I, ZonedTime> extractor) {
            return bind(column, ColumnType.TIME, extractor, null, Zoning.ZONE_ID, false);
        }

        /**
         * Binds a DATE_TIME column to a getter returning a date and time without a zone. A cell with a zone
         * ({@code date and time("2025-01-31T08:30:00Z")}) is compared with such a value by its local date and
         * time, as FEEL does when only one of the two has a zone.
         */
        public Builder<I, O> dateTimeInput(String column, Function<? super I, LocalDateTime> extractor) {
            return bind(column, ColumnType.DATE_TIME, extractor);
        }

        /**
         * Binds a DATE_TIME column to a getter returning a date and time with an offset or a zone id,
         * compared by instant. An {@code OffsetDateTime} binds through {@code OffsetDateTime::toZonedDateTime}.
         * A cell without a zone ({@code date and time("2025-01-31T08:30:00")}) is compared with the local
         * date and time, so it matches {@code 2025-01-31T08:30:00Z} and {@code 2025-01-31T08:30:00@Europe/Paris}.
         */
        public Builder<I, O> zonedDateTimeInput(String column, Function<? super I, ZonedDateTime> extractor) {
            return bind(column, ColumnType.DATE_TIME, extractor, null, Zoning.ZONED, false);
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
            return bind(column, ColumnType.STRING, extractor, values, Zoning.LOCAL, false);
        }

        /**
         * Binds a column that holds lists of strings, such as a customer's nationalities, to a getter. A
         * cell holds when some element passes one of its tests: {@code "PT","ES"} matches a list holding
         * {@code "PT"} or {@code "ES"}, and {@code not("PT")} a list without {@code "PT"}. A missing (null)
         * list matches only {@code -} and {@code null}; an empty one matches no test, so every
         * {@code not(...)}. An element may be missing: null, or blank text by Praecepta's convention for missing
         * values (FEEL's {@code ""} is a string). FEEL answers for a missing element as for a missing value: a
         * literal is false for it ({@code not("PT")} matches {@code ["BR", null]}) and a comparison unknown, so
         * {@code >= 18} matches
         * {@code [20, null]} but {@code not(>= 18)} does not match {@code [10, null]}; in a DMN table,
         * {@code list contains(?, null)} asks for one. Input values, if declared, apply to the elements that are
         * there. A cell of another column that names this input ({@code Flu Symptoms}, in a DMN table)
         * holds when that column's value is in the list.
         */
        public Builder<I, O> listInput(String column, Function<? super I, ? extends Collection<String>> extractor) {
            return bind(column, ColumnType.STRING, extractor, null, Zoning.LOCAL, true);
        }

        /**
         * {@link #listInput(String, Function)} for lists of {@code elementType}, whose elements are what the
         * getter of a single value of that type returns ({@code Number}, {@code LocalDate}, {@code Period}...),
         * or their text; evaluating a list with an element of another class throws an
         * {@link InvalidInputException}. The elements of a TIME or DATE_TIME list may come with a time zone ({@code OffsetTime},
         * {@code ZonedDateTime}) or without one, each compared as a single value of its kind is.
         */
        public Builder<I, O> listInput(String column, ColumnType elementType, Function<? super I, ? extends Collection<?>> extractor) {
            return bind(column, Objects.requireNonNull(elementType, "elementType"), extractor, null, Zoning.EITHER, true);
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
         * With {@link #inputs(InputStrategy)}: the column (or an input the cells compare with) holds lists
         * of {@code elementType}, matched as {@link #listInput(String, Function)} describes. Its text is a
         * FEEL list such as {@code ["PT", "BR"]} or {@code [1, 2.5]}, whose brackets may be left out
         * ({@code PT, BR}); {@code []} is an empty list, null or blank text a missing one, and {@code null} in
         * a list a missing element ({@code ["PT", null]}).
         */
        public Builder<I, O> listColumnType(String column, ColumnType elementType) {
            columnType(column, elementType);
            listColumns.add(column);
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
         * Whether {@link #build()} logs the validation report, if it has issues, as one warning through
         * {@link System.Logger} (logger {@code io.github.brunopacheco1.praecepta.engine.PraeceptaEngine}).
         * It does by default; turn it off when the caller reports {@link PraeceptaEngine#validationReport()}
         * itself, as the framework integrations and the build plugins do, so that each issue is logged once.
         * The report is the same either way, and {@link #failOn} still applies.
         */
        public Builder<I, O> logValidationReport(boolean log) {
            this.logValidationReport = log;
            return this;
        }

        /**
         * For {@link DecisionGraph}: binds a column, or an input the cells compare with, to a getter of values of its type
         * in any of the forms the typed bindings take (a time or date-time with a time zone or without one), or of lists
         * of them, as a map-based input reads them from text, so the analysis looks at the same values as for a table
         * bound with {@link #inputs(InputStrategy)}.
         */
        Builder<I, O> valueInput(String column, ColumnType type, boolean list, Function<? super I, ?> extractor) {
            return bind(column, type, extractor, null, Zoning.EITHER, list);
        }

        /**
         * For {@link DecisionGraph}: a value the table's FEEL (its cells beyond the index, input expressions and output
         * entries) reads beside its columns, such as a requirement a cell reads a path into ({@code > Applicant.limit}):
         * given as it is, a FEEL value of {@code type}, and in scope when those are parsed.
         */
        Builder<I, O> expressionInput(String name, FeelType type, Function<? super I, ?> getter) {
            expressionInputs.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(getter, "getter"));
            expressionInputTypes.put(name, Objects.requireNonNull(type, "type"));
            return this;
        }

        /**
         * For {@link DecisionGraph}: the types its model defines (its item definitions), by name, which the table's FEEL
         * may name ({@code Amount instance of tAmount}).
         */
        Builder<I, O> types(Map<String, FeelType> types) {
            this.types = Map.copyOf(types);
            return this;
        }

        /**
         * For {@link DecisionGraph}: the table's FEEL as the graph's analysis parsed it, which the engine takes instead of
         * parsing it again, and the scope it was parsed in, which the engine parses the rest in.
         */
        Builder<I, O> parsed(TableExpressions.Parsed parsed) {
            this.parsed = Objects.requireNonNull(parsed, "parsed");
            return this;
        }

        /** Test hook: stores the masks in this form instead of choosing by density ({@link Masks.Form#AUTO}). */
        Builder<I, O> maskForm(Masks.Form form) {
            this.maskForm = Objects.requireNonNull(form, "form");
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
            return bind(column, type, extractor, null, Zoning.LOCAL, false);
        }

        private Builder<I, O> bind(String column, ColumnType type, Function<? super I, ?> extractor, List<String> enumValues,
                Zoning zoning, boolean list) {
            Objects.requireNonNull(column, COLUMN);
            Objects.requireNonNull(extractor, "extractor");
            if (bindings.putIfAbsent(column, new Binding<>(type, extractor, enumValues, zoning, list)) != null) {
                throw new IllegalArgumentException("Input column '%s' is declared twice".formatted(column));
            }
            return this;
        }

        private void checkInputBindings() {
            // a table without input columns, or only with computed ones, which a graph gives what its expressions read,
            // needs no binding
            boolean noColumns = tableTypes != null && tableTypes.keySet().stream().allMatch(TableExpressions::isExpression)
                    && inputStrategy == null;
            require(bindings.isEmpty() != (inputStrategy == null) || noColumns,
                    "bind inputs either per column with input(...) or with inputs(...), not both and not neither");
            require(columnTypes.isEmpty() || inputStrategy != null,
                    "columnType(...) and listColumnType(...) only apply with inputs(...); per-column bindings carry their type");
            if (tableTypes == null) {
                return;
            }
            if (inputStrategy != null) {
                for (var column : listColumns) {
                    require(!tableHoldsSingles(column), "input '%s' holds single values in the table; listColumnType(...) cannot make it a list"
                            .formatted(column));
                }
                return;
            }
            for (var tableColumn : tableTypes.entrySet()) {
                var column = tableColumn.getKey();
                var type = tableColumn.getValue();
                var binding = bindings.get(column);
                if (binding == null && TableExpressions.isExpression(column)) {
                    continue; // computed from the inputs its expression reads
                }
                require(binding != null, "column '%s' of the table has no input binding".formatted(column));
                require(binding.type() == type, "column '%s' is a %s column in the table but is bound as %s"
                        .formatted(column, type, binding.type()));
            }
            bindings.forEach((input, binding) -> {
                // the table's columns, and the inputs its cells compare with
                if (tableHoldsSingles(input) || tableLists.contains(input)) {
                    boolean list = tableLists.contains(input);
                    require(binding.list() == list, list
                            ? "input '%s' holds lists in the table; bind it with listInput(...)".formatted(input)
                            : "input '%s' holds single values in the table but is bound with listInput(...)".formatted(input));
                }
            });
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
         * @param type       the column's type, or its elements' for a list
         * @param enumValues for an enum-bound column, each constant's value by ordinal; else null
         * @param zoning     whether the getter's times and date-times (or a list's elements) have a time zone
         * @param list       whether the getter returns lists ({@code listInput(...)})
         */
        private record Binding<I>(ColumnType type, Function<? super I, ?> extractor, List<String> enumValues, Zoning zoning,
                boolean list) {
        }
    }
}
