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
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionTypeException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;
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
 * {@code null}, and {@code -} or blank for any value. See {@code CellParser}. A column bound with
 * {@link Builder#listInput(String, Function)} holds lists, and its cells hold when some element passes
 * them.
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
    /** The result when no rule matches: the default output if there is one, else empty, whatever the aggregation. */
    private final List<O> noMatch;
    private final ValidationReport report;

    private PraeceptaEngine(Builder<I, O> builder) {
        hitPolicy = builder.hitPolicy;
        var rules = List.copyOf(builder.rules);

        var types = columnTypes(builder, rules);
        var lists = listInputs(builder);
        var cells = parseCells(rules, types, builder.feelCells, lists);
        // cells that compare with another input are decided when evaluating; the index sees "any value"
        var variableCells = takeVariableCells(rules, types, cells, builder.inputStrategy != null, lists);
        var allowedValues = allowedValues(builder, types.keySet());
        inputs = List.copyOf(types.keySet());
        var evaluationOrder = orderByWildcards(inputs, cells, lists);

        var numeric = numericOutputs(builder.tableOutputTypes, rules, types);
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
        // readers return raw values; each column converts them (see ColumnIndex#maskFor)
        extractorReader = inputStrategy == null ? extractorReader(builder.bindings, evaluationOrder) : null;
        mapReader = inputStrategy == null ? null : mapReader(evaluationOrder);

        outputStrategy = builder.outputStrategy;
        int ruleCount = rules.size();
        outputByBit = new Object[ruleCount];
        @SuppressWarnings({"rawtypes", "unchecked"}) // a generic array cannot be created otherwise
        List<O>[] singles = new List[ruleCount];
        singleByBit = singles;
        computedByBit = new ComputedOutputs[ruleCount];
        ruleOutputByBit = new PraeceptaOutput[ruleCount];
        idByBit = new Integer[ruleCount];
        boolean anyReads = false;
        for (int bit = 0; bit < ruleCount; bit++) {
            var rule = rules.get(bitToRule[bit]);
            idByBit[bit] = rule.priority();
            ruleOutputByBit[bit] = rule.output();
            if (!rule.output().readsInputs()) {
                var output = outputStrategy.transform(rule.output());
                outputByBit[bit] = output;
                singleByBit[bit] = List.of(output);
            } else {
                computedByBit[bit] = computedOutputs(rule, evaluationOrder, lists, types);
                anyReads = true;
            }
        }
        readsInputs = anyReads;
        numericOutputs = numeric;
        // with outputs that read inputs, ANY compares the outputs of each evaluation instead
        sameOutputsByBit = hitPolicy == HitPolicy.ANY && !readsInputs
                ? sameOutputMasks(rules, bitToRule, index.words(), builder.maskForm, numericOutputs)
                : null;
        reduce = reducer(builder.aggregation, builder.outputStrategy);
        count = builder.aggregation == AggregationOperator.COUNT ? builder.outputStrategy::count : null;
        if (count != null) {
            count.apply(1); // so that a strategy without count fails here, not when a rule first matches
        }
        // DMN 1.5, 10.3.2.10, step 2: no match gives the default output, else null, before any aggregation
        noMatch = builder.defaultOutput == null ? List.of() : List.of(outputStrategy.transform(builder.defaultOutput));
        variableCellsByBit = variableCellsByBit(variableCells, bitToRule, evaluationOrder);
        variableRules = variableRules(variableCellsByBit, words);
        hasVariableCells = !BitsetIndex.isEmpty(variableRules);
        // rules that test a list column match lists the index's classes cannot describe: the analysis leaves them out too
        var unseenRules = listRules(index);
        BitsetIndex.or(unseenRules, variableRules);
        report = validate(index, rules, hitPolicy, unseenRules, numericOutputs, builder.failOn, builder.logValidationReport);
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
     *         aggregation (COUNT included: DMN 1.5, 10.3.2.10, gives null, not zero)
     * @throws HitPolicyViolationException if the input matches several rules under UNIQUE, or rules
     *                                     with different outputs under ANY
     * @throws InvalidInputException       if a value is outside its column's declared input values, or
     *                                     not a valid value of the column's type (for a list, if an
     *                                     element is missing or of another type)
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
                if (readsInputs ? !sameOutputs(matches, input, reader) : !Masks.isSubset(matches, sameOutputsByBit[bit])) {
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
                    selected[i++] = computedByBit[bit] == null ? outputByBit[bit] : outputStrategy.transform(output(bit, input, reader));
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
     * list, so {@code > limits} or {@code [low..limits]} would never hold.
     */
    private static void rejectComparisonsWithLists(Praeceptum rule, String column, UnaryTests cell, Set<String> lists) {
        for (var test : cell.tests()) {
            if (test instanceof UnaryTests.Equals) {
                continue;
            }
            for (var name : UnaryTests.names(test)) {
                if (lists.contains(name)) {
                    throw new InvalidDecisionTableException(("Rule %s, column '%s': compares with '%s', which holds lists; "
                            + "a cell can only ask whether the value is in it, as '%s' does").formatted(rule.priority(), column, name, name));
                }
            }
        }
    }

    /** The rules (bits) whose cell in a column that holds lists is not "any value". */
    private static long[] listRules(BitsetIndex index) {
        var rules = new long[index.words()];
        var all = index.allRules();
        for (int c = 0; c < index.columnCount(); c++) {
            var column = index.column(c);
            if (column.holdsLists()) {
                var constrained = all.clone();
                BitsetIndex.andNot(constrained, column.rulesAcceptingAnyList());
                BitsetIndex.or(rules, constrained);
            }
        }
        return rules;
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

    // ---- outputs that read inputs: outputs that name an input, and expressions ---------------------

    /**
     * One output of a rule that reads inputs: the output column, the expression that computes it (a
     * {@link OutputExpression.Name} for an output that names an input), and the position, in the
     * evaluation order, of each input it reads.
     *
     * @param reference whether the output names an input, so it takes that input's value as its text
     *                  ({@link FeelLiterals#text}, a number keeping its scale); else it is an expression,
     *                  and a computed number is written without trailing zeros
     */
    private record Computed(String output, OutputExpression expression, Map<String, Integer> columns, boolean reference) {
    }

    /**
     * A rule's outputs that read inputs: its other outputs, as written, and the computed ones. A class,
     * not a record: it holds an array and is never compared.
     */
    private static final class ComputedOutputs {
        private final Map<String, String> values;
        private final Computed[] computed;

        ComputedOutputs(Map<String, String> values, Computed[] computed) {
            this.values = values;
            this.computed = computed;
        }
    }

    /**
     * The outputs of {@code rule} that read inputs, checked: each reads inputs of the engine that hold
     * single values (the table's columns, or inputs its cells compare with), an expression parses and its
     * inputs' types go together, and none is in an output priority list (see {@link #priorityKey}), where
     * its place cannot be known before evaluation. {@code DmnReader} accepts the same names.
     */
    private static ComputedOutputs computedOutputs(Praeceptum rule, List<String> evaluationOrder, Set<String> lists,
            Map<String, ColumnType> types) {
        var computed = new ArrayList<Computed>();
        rule.output().references().forEach((output, input) -> computed.add(new Computed(output, new OutputExpression.Name(input),
                Map.of(input, inputColumn(rule, output, input, evaluationOrder, lists)), true)));
        rule.output().expressions().forEach((output, text) -> {
            var where = "Rule %s, output '%s'".formatted(rule.priority(), output);
            OutputExpression expression;
            try {
                expression = OutputExpression.parse(text);
            } catch (ExpressionSyntaxException e) {
                throw new InvalidDecisionTableException(where + ": " + e.getMessage());
            }
            var columns = new HashMap<String, Integer>();
            expression.names().forEach(name -> columns.put(name, inputColumn(rule, output, name, evaluationOrder, lists)));
            try {
                expression.type(types::get);
            } catch (ExpressionTypeException e) {
                throw new InvalidDecisionTableException(where + ": " + e.getMessage());
            }
            computed.add(new Computed(output, expression, Map.copyOf(columns), false));
        });
        return new ComputedOutputs(rule.output().values(), computed.toArray(Computed[]::new));
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
    private static Set<String> numericOutputs(Map<String, ColumnType> declared, List<Praeceptum> rules, Map<String, ColumnType> types) {
        var numeric = new HashSet<String>();
        for (var rule : rules) {
            rule.output().references().forEach((output, input) -> {
                if (types.get(input) == ColumnType.NUMBER) {
                    numeric.add(output);
                }
            });
            rule.output().expressions().forEach((output, text) -> {
                if (isNumber(text, types)) {
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

    /** Whether an expression gives a number; false if it is invalid, which indexing its rule reports. */
    private static boolean isNumber(String expression, Map<String, ColumnType> types) {
        try {
            return OutputExpression.parse(expression).type(types::get) == ColumnType.NUMBER;
        } catch (IllegalArgumentException e) {
            return false;
        }
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
    private <T> List<O> single(int bit, T input, BitsetIndex.ColumnReader<T> reader) {
        var ready = singleByBit[bit];
        return ready != null ? ready : List.of(outputStrategy.transform(output(bit, input, reader)));
    }

    /**
     * The outputs of a rule that reads inputs, filled in with the text of the input's values and of the
     * expressions' values; a computed number has no trailing zeros, since FEEL numbers are values. An
     * output that names an input gives the input's value as {@link FeelLiterals#text} writes it, a number
     * keeping its scale, as a literal output keeps it.
     */
    private <T> PraeceptaOutput output(int bit, T input, BitsetIndex.ColumnReader<T> reader) {
        var computedOutputs = computedByBit[bit];
        var values = new HashMap<>(computedOutputs.values);
        for (var computed : computedOutputs.computed) {
            var columns = computed.columns();
            var value = computed.expression().evaluate(name -> valueOf(columns.get(name), input, reader));
            values.put(computed.output(), switch (value) {
                case null -> null;
                case BigDecimal number when !computed.reference() -> numberText(number);
                default -> FeelLiterals.text(value);
            });
        }
        return new PraeceptaOutput(values);
    }

    /**
     * ANY, when outputs read inputs: whether every matching rule gives this input the same outputs, number
     * outputs compared by value.
     */
    private <T> boolean sameOutputs(long[] matches, T input, BitsetIndex.ColumnReader<T> reader) {
        Map<String, String> first = null;
        for (int bit = BitsetIndex.first(matches); bit >= 0; bit = BitsetIndex.next(matches, bit + 1)) {
            var output = computedByBit[bit] == null ? ruleOutputByBit[bit] : output(bit, input, reader);
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

    /**
     * Parsed cells per column, in rule order: strict FEEL for a DMN table (a list column's with
     * {@code list contains(?, ...)}), else Praecepta's syntax.
     */
    private static Map<String, List<UnaryTests>> parseCells(List<Praeceptum> rules, Map<String, ColumnType> types, boolean feel,
            Set<String> lists) {
        var cells = new LinkedHashMap<String, List<UnaryTests>>();
        types.forEach((column, type) -> cells.put(column, rules.stream()
                .map(rule -> parseCell(rule, column, type, feel, lists.contains(column)))
                .toList()));
        return cells;
    }

    private static UnaryTests parseCell(Praeceptum rule, String column, ColumnType type, boolean feel, boolean list) {
        try {
            var cell = rule.input().values().get(column);
            if (!feel) {
                return CellParser.parse(cell, type);
            }
            return list ? CellParser.parseFeelList(cell, type) : CellParser.parseFeel(cell, type);
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
     * as {@code failOn}; otherwise logs the findings as a warning, unless {@code log} is false.
     */
    private static ValidationReport validate(BitsetIndex index, List<Praeceptum> rules, HitPolicy hitPolicy, long[] variableRules,
            Set<String> numericOutputs, Severity failOn, boolean log) {
        var analysed = TableAnalysis.analyse(index, rules, hitPolicy, variableRules, output -> comparable(output, numericOutputs));
        var report = new ValidationReport(analysed.stream()
                .sorted(Comparator.comparing(Issue::severity).reversed())
                .toList());
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
         * ({@link DecisionTable#defaultOutput}).
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
         * or none, COUNT included (DMN 1.5, 10.3.2.10, step 2).
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
         * {@code not(...)}. Elements may not be null or blank. Input values, if declared, apply to the
         * elements. A cell of another column that names this input ({@code Flu Symptoms}, in a DMN table)
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
         * ({@code PT, BR}); {@code []} is an empty list, and null or blank text a missing one.
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
            require(bindings.isEmpty() != (inputStrategy == null),
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
