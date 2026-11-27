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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.ComputedColumn.Computing;
import io.github.brunopacheco1.praecepta.engine.DeferredCells.FeelCell;
import io.github.brunopacheco1.praecepta.engine.DeferredCells.VariableCell;
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
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

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

    /*
     * Where to look, in this class and the package-private classes around it.
     *
     * How an engine is built: the constructor, a numbered sequence. TableColumns types the columns and parses their
     * cells, HitPolicies orders the rules' bits, ColumnIndex and BitsetIndex index the rules, and TableAnalysis
     * validates the table.
     *
     * How an input is evaluated: evaluate(...). BitsetIndex.match intersects one mask per column
     * (ColumnIndex.maskFor), keepVariableMatches applies the cells the index leaves to evaluation (DeferredCells),
     * and the hit policy chooses among the rules that are left.
     *
     * How outputs are computed: the constructor converts each rule's outputs once (fillOutputsByBit). Outputs that
     * read inputs (ComputedOutputs) are computed in output(...) on every evaluation, and columns whose input
     * expression computes their value (ComputedColumn) in ComputingReader.
     */

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

    /**
     * Builds the engine from the builder's settings, in the order below; each step may reject the table with an
     * {@link InvalidDecisionTableException}.
     */
    private PraeceptaEngine(Builder<I, O> builder) {
        hitPolicy = builder.hitPolicy;
        var rules = List.copyOf(builder.rules);

        // 1. the columns: their types and parsed cells, and what the table's FEEL reads
        var columns = TableColumns.read(builder, rules);
        var expressions = columns.expressions();
        var evaluationOrder = columns.evaluationOrder();
        inputs = columns.names();
        givenValues = givenValues(builder);
        nameReads = TableColumns.nameReads(expressions, evaluationOrder, List.copyOf(builder.expressionInputs.keySet()), columns.computed());
        var nameTypes = columns.nameTypes(builder.expressionInputTypes);

        // 2. the index: a bit per rule, ordered so that the lowest set bit of a match is the rule the hit policy takes first
        numericOutputs = ComputedOutputs.numericOutputs(builder.tableOutputTypes, rules, columns.types(), expressions, nameTypes);
        int[] bitToRule = HitPolicies.bitToRule(rules, builder.outputPriority, numericOutputs);
        index = new BitsetIndex(columns.indexes(builder, bitToRule), bitToRule);

        // 3. the readers, which return raw values; each column converts them (see ColumnIndex#maskFor)
        inputStrategy = builder.inputStrategy;
        computedColumns = ComputedColumn.byPosition(columns.computed(), evaluationOrder, columns.types(), columns.lists());
        extractorReader = inputStrategy == null ? extractorReader(builder.bindings, evaluationOrder) : null;
        mapReader = inputStrategy == null ? null : mapReader(evaluationOrder);
        computingExtractorReader = computingReader(extractorReader);
        computingMapReader = computingReader(mapReader);

        // 4. the outputs of each rule: converted once, or computed on every evaluation if they read inputs
        outputStrategy = builder.outputStrategy;
        int ruleCount = rules.size();
        outputByBit = new Object[ruleCount];
        @SuppressWarnings({"rawtypes", "unchecked"}) // a generic array cannot be created otherwise
        List<O>[] singles = new List[ruleCount];
        singleByBit = singles;
        computedByBit = new ComputedOutputs[ruleCount];
        ruleOutputByBit = new PraeceptaOutput[ruleCount];
        idByBit = new Integer[ruleCount];
        var outputIssues = new ArrayList<Issue>();
        readsInputs = fillOutputsByBit(rules, bitToRule, new ComputedOutputs.Context(evaluationOrder, columns.lists(), expressions,
                nameTypes, builder.tableOutputTypes, builder.untypedOutputs, outputIssues));

        // 5. what the hit policy needs to choose among the matching rules
        // with outputs that read inputs, ANY compares the outputs of each evaluation instead
        sameOutputsByBit = hitPolicy == HitPolicy.ANY && !readsInputs
                ? HitPolicies.sameOutputMasks(rules, bitToRule, index.words(), builder.maskForm, numericOutputs)
                : null;
        reduce = HitPolicies.reducer(builder.aggregation, outputStrategy);
        aggregatedOutput = reduce == null ? null : HitPolicies.onlyOutput(builder.tableOutputTypes, rules);
        missingAggregateByBit = HitPolicies.missingAggregates(rules, bitToRule, aggregatedOutput, computedByBit);
        count = builder.aggregation == AggregationOperator.COUNT ? outputStrategy::count : null;
        if (count != null) {
            count.apply(1); // so that a strategy without count fails here, not when a rule first matches
        }
        // DMN 1.5, 10.3.2.10, step 2: no match gives the default output, else null, before any aggregation
        noMatch = builder.defaultOutput == null ? List.of() : List.of(outputStrategy.transform(builder.defaultOutput));

        // 6. the cells the index leaves to evaluation
        variableCellsByBit = DeferredCells.variableCellsByBit(columns.variableCells(), bitToRule, evaluationOrder);
        feelCellsByBit = DeferredCells.feelCellsByBit(expressions, bitToRule, evaluationOrder);
        variableRules = DeferredCells.variableRules(variableCellsByBit, feelCellsByBit, index.words());
        hasVariableCells = !BitsetIndex.isEmpty(variableRules);

        // 7. the static analysis
        // a rule that tests a computed column tests the columns it is computed from, which the index sees as unrelated
        var unanalysed = DeferredCells.computedColumnRules(builder.feelCells ? columns.cells().keySet() : columns.computed().keySet(),
                columns.cells(), bitToRule, variableRules);
        var analysed = analyse(index, rules, hitPolicy, unanalysed, numericOutputs, builder.defaultOutput != null, outputIssues);
        report = enforce(analysed, builder.failOn, builder.logValidationReport);
    }

    /**
     * Fills in, by bit, each rule's id and output, and the output transformed once if it reads no input, or how to
     * compute it if it does.
     *
     * @return whether some rule's output reads inputs
     */
    private boolean fillOutputsByBit(List<Praeceptum> rules, int[] bitToRule, ComputedOutputs.Context context) {
        boolean anyReads = false;
        for (int bit = 0; bit < rules.size(); bit++) {
            var rule = rules.get(bitToRule[bit]);
            idByBit[bit] = rule.priority();
            ruleOutputByBit[bit] = rule.output();
            if (rule.output().readsInputs()) {
                computedByBit[bit] = ComputedOutputs.of(rule, bitToRule[bit], context);
                anyReads = true;
            } else {
                var output = outputStrategy.transform(rule.output());
                outputByBit[bit] = output;
                singleByBit[bit] = List.of(output);
            }
        }
        return anyReads;
    }

    // ---- the public API -------------------------------------------------------------------------------

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

    // ---- evaluation ---------------------------------------------------------------------------------

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

    /** The single-hit result of the rule at {@code bit}: ready-made, unless it reads inputs. */
    private <T> List<O> single(int bit, T input, BitsetIndex.ColumnReader<T> reader, Consumer<String> errors) {
        var ready = singleByBit[bit];
        return ready != null ? ready : List.of(transform(output(bit, input, reader, errors)));
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

    /**
     * ANY, when outputs read inputs: whether every matching rule gives this input the same outputs, number
     * outputs compared by value.
     */
    private <T> boolean sameOutputs(long[] matches, T input, BitsetIndex.ColumnReader<T> reader) {
        Map<String, String> first = null;
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            var output = computedByBit[bit] == null ? ruleOutputByBit[bit] : output(bit, input, reader, null).text();
            var values = OutputText.comparable(output, numericOutputs).values();
            if (first == null) {
                first = values;
            } else if (!first.equals(values)) {
                return false;
            }
        }
        return true;
    }

    private HitPolicyViolationException violation(long[] matches) {
        var ids = new ArrayList<Integer>();
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            ids.add(idByBit[bit]);
        }
        return new HitPolicyViolationException(hitPolicy, ids);
    }

    // ---- cells the index leaves to evaluation ----------------------------------------------------------

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

    // ---- outputs that read inputs -------------------------------------------------------------------

    /** The outputs of a rule that reads inputs, computed: the value of the input each names, or of its expression. */
    private <T> ComputedOutputs.Output output(int bit, T input, BitsetIndex.ColumnReader<T> reader, Consumer<String> errors) {
        var computedOutputs = computedByBit[bit];
        var values = new Object[computedOutputs.computed.length];
        for (int i = 0; i < values.length; i++) {
            var computed = computedOutputs.computed[i];
            var value = computed.reference()
                    ? valueOf(computed.column(), input, reader)
                    : computed.expression().evaluate(name -> read(name, input, reader), errors);
            values[i] = ComputedOutputs.bound(value, computed, errors);
        }
        return new ComputedOutputs.Output(computedOutputs, values);
    }

    /**
     * Converts computed outputs with the output strategy: their values, and the other outputs' text, for one that takes
     * values ({@link ValueOutputs}), which so never has them written as text; else all of them as text.
     */
    private O transform(ComputedOutputs.Output output) {
        return outputStrategy instanceof ValueOutputs<O> values
                ? values.transform(output.outputs.literals, output.values())
                : outputStrategy.transform(output.text());
    }

    // ---- reading the input ----------------------------------------------------------------------------

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

    /**
     * Reads a column, by its position in the evaluation order, with its bound getter; a computed column, which has none,
     * is never read with it ({@link ComputingReader}).
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

    /** A reader that also computes the computed columns, over {@code reader}; null without one or without computed columns. */
    private <T> BitsetIndex.ColumnReader<Computing<T>> computingReader(BitsetIndex.ColumnReader<T> reader) {
        return reader == null || computedColumns.length == 0 ? null : new ComputingReader<>(reader);
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
            if (value == Computing.NOT_COMPUTED) {
                value = computedColumn.value(computedColumn.expression().evaluate(name -> PraeceptaEngine.this.read(name, computing, this)));
                computing.values[column] = value;
            }
            return value;
        }
    }

    // ---- validation ----------------------------------------------------------------------------

    /** Runs the static analysis: its findings, and those of the output entries, most severe first. */
    private static ValidationReport analyse(BitsetIndex index, List<Praeceptum> rules, HitPolicy hitPolicy, long[] variableRules,
            Set<String> numericOutputs, boolean hasDefaultOutput, List<Issue> outputIssues) {
        var analysed = new ArrayList<>(
                TableAnalysis.analyse(index, rules, hitPolicy, variableRules, output -> OutputText.comparable(output, numericOutputs)));
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

        // ---- settings that only the engine's constructor and this builder read

        private HitPolicy hitPolicy;
        private AggregationOperator aggregation;
        private List<Praeceptum> rules;
        private Severity failOn;
        private boolean logValidationReport = true;
        /** The output when no rule matches, or null. */
        private PraeceptaOutput defaultOutput;
        private OutputStrategy<O> outputStrategy;
        private final Map<String, List<String>> outputPriority = new LinkedHashMap<>();
        /** The outputs a {@link #table(DecisionTable)} gives no type, whose computed values keep their own. */
        private Set<String> untypedOutputs = Set.of();
        /** The types of the values of {@link #expressionInputs}. */
        private final Map<String, FeelType> expressionInputTypes = new HashMap<>();
        /** The inputs a {@link #table(DecisionTable)} declares as lists, checked against the bindings. */
        private Set<String> tableLists = Set.of();
        /** The {@link #table(DecisionTable)}, whose cells name the inputs they compare with. */
        private DecisionTable table;
        /**
         * The inputs a {@link #table(DecisionTable)}'s cells compare with; null until a binding that is not
         * one of its columns asks, since finding them parses every cell.
         */
        private Set<String> tableNamedInputs;

        // ---- settings that TableColumns reads too, to work out the table's columns: hence not private

        final Map<String, Binding<I>> bindings = new LinkedHashMap<>();
        InputStrategy<I> inputStrategy;
        final Map<String, ColumnType> columnTypes = new LinkedHashMap<>();
        final Map<String, String> allowedValues = new LinkedHashMap<>();
        /** Column types of a {@link #table(DecisionTable)}, checked against the bindings. */
        Map<String, ColumnType> tableTypes;
        /** Output column types of a {@link #table(DecisionTable)}: its number outputs are compared by value. */
        Map<String, ColumnType> tableOutputTypes = Map.of();
        /** With {@link #inputs(InputStrategy)}: the inputs that hold lists. */
        final Set<String> listColumns = new LinkedHashSet<>();
        /** Whether the rules' cells are strict FEEL, as a DMN table's are. */
        boolean feelCells;
        /** For {@link DecisionGraph}: the getters of the values the table's FEEL reads beside its columns, by name. */
        final Map<String, Function<? super I, ?>> expressionInputs = new LinkedHashMap<>();
        /** For {@link DecisionGraph}: the types its model defines (item definitions), which the table's FEEL may name. */
        Map<String, FeelType> types = Map.of();
        /** For {@link DecisionGraph}: the table's FEEL as its analysis parsed it, and the scope it parsed it in. */
        TableExpressions.Parsed parsed = TableExpressions.Parsed.NONE;
        Masks.Form maskForm = Masks.Form.AUTO;

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
            var inputTypes = new LinkedHashMap<String, ColumnType>();
            for (var input : table.inputs()) {
                inputTypes.put(input.name(), input.type());
                if (input.allowedValues() != null) {
                    allowedValues(input.name(), input.allowedValues());
                }
            }
            tableTypes = inputTypes;
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
            return allowedValues(column, TableColumns.quotedList(values));
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
            checkBindingsMatchTable();
        }

        /**
         * Checks that each of the table's columns, but those its input expressions compute, has a binding of its type,
         * and that each input the table holds is bound as a list or as single values, as the table holds it.
         */
        private void checkBindingsMatchTable() {
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
        record Binding<I>(ColumnType type, Function<? super I, ?> extractor, List<String> enumValues, Zoning zoning,
                boolean list) {
        }
    }
}
