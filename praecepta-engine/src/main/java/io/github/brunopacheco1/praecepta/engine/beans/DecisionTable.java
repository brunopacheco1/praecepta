/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * A complete decision table: columns with their types and value lists, hit policy and rules. This is
 * what a DMN decision table holds; {@code DmnReader} and {@code DmnWriter} convert between the two,
 * and {@code PraeceptaEngine.Builder#table} configures an engine from it.
 *
 * @param name        the decision's name
 * @param hitPolicy   the hit policy
 * @param aggregation COLLECT aggregation, or null
 * @param inputs      input columns, in table order
 * @param outputs     output columns, in table order, with their value lists and default values ({@link #defaultOutput})
 * @param rules       rules in rule order; input cells are unary tests of their column's type, outputs
 *                    are plain values as text (a string, or a number, boolean, date or time such as
 *                    {@code 2025-01-31}), null when empty, unless they name an input or are expressions
 *                    ({@link PraeceptaOutput#references}, {@link PraeceptaOutput#expressions})
 * @param feelCells   whether the input cells are strict FEEL, as a DMN file holds them (strings quoted,
 *                    {@code date(...)} calls), where a name compares with another input, as in
 *                    {@code > dateE}; otherwise Praecepta's syntax, where an unquoted word is a string
 * @param listInputs  the inputs that hold a list of values rather than one value: columns of the table,
 *                    whose values are lists of their type, and inputs the cells compare with (see
 *                    {@link #namedInputs}), whose values are lists of the type of the column that names
 *                    them. A list column's cell holds when some element passes one of its tests
 *                    ({@code "PT","ES"}: the list holds {@code "PT"} or {@code "ES"}), and its
 *                    {@code not(...)} when no element does; a missing list passes only {@code -} and
 *                    {@code null}, and an empty one fails every test. An element may be missing, for
 *                    which a literal is false and a comparison unknown, as in FEEL. In strict FEEL such a
 *                    cell is written with {@code list contains(?, "PT")} (see {@code CellParser#parseFeelList}),
 *                    and {@code list contains(?, null)} asks for a missing element.
 *                    A cell that names a list input, as {@code Flu Symptoms} in a string column, holds
 *                    when the column's value is in that list, as in DMN.
 */
public record DecisionTable(
        String name,
        HitPolicy hitPolicy,
        AggregationOperator aggregation,
        List<InputColumn> inputs,
        List<OutputColumn> outputs,
        List<Praeceptum> rules,
        boolean feelCells,
        Set<String> listInputs) {

    public DecisionTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hitPolicy, "hitPolicy");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        rules = List.copyOf(rules);
        listInputs = Set.copyOf(listInputs);
    }

    /** A table whose inputs each hold one value. */
    public DecisionTable(String name, HitPolicy hitPolicy, AggregationOperator aggregation, List<InputColumn> inputs,
                         List<OutputColumn> outputs, List<Praeceptum> rules, boolean feelCells) {
        this(name, hitPolicy, aggregation, inputs, outputs, rules, feelCells, Set.of());
    }

    /** A table whose cells are in Praecepta's syntax, and whose inputs each hold one value. */
    public DecisionTable(String name, HitPolicy hitPolicy, AggregationOperator aggregation, List<InputColumn> inputs,
                         List<OutputColumn> outputs, List<Praeceptum> rules) {
        this(name, hitPolicy, aggregation, inputs, outputs, rules, false);
    }

    /**
     * The rule's cell in a column, parsed as the table holds it: strict FEEL or Praecepta's syntax, and
     * for a list column (strict FEEL) with {@code CellParser#parseFeelList}, as the simple unary tests the bitset index
     * evaluates. A strict FEEL cell beyond them is FEEL unary tests, which {@link FeelUnaryTests#parseCell} reads, with
     * the exception this throws for it.
     *
     * @throws CellSyntaxException if the cell is invalid, or beyond the simple unary tests
     */
    public UnaryTests cell(Praeceptum rule, InputColumn column) {
        var text = rule.input().values().get(column.name());
        if (!feelCells) {
            return CellParser.parse(text, column.type());
        }
        return listInputs.contains(column.name()) ? CellParser.parseFeelList(text, column.type()) : CellParser.parseFeel(text, column.type());
    }

    /**
     * The inputs the table reads that are not columns of the table, in the order first named: those its simple cells
     * compare with ({@code > limit}), each with the type of the column that compares with it, then, in a strict FEEL
     * table, those its other cells ({@link FeelUnaryTests}), its input expressions that are expressions
     * ({@code Age * 12}) and its output entries that compute their value read ({@code > Limit * 2},
     * {@code Base + Rate}), each with the type of the column or output whose entry reads it first, which is the type an
     * engine reading its inputs from text reads it with. Empty unless {@link #feelCells}. Worked out once per table, as
     * it parses every cell: the map is unmodifiable, and a table equal to one asked before gives the same.
     * A type an expression names ({@code instance of}) is not checked here, as the table holds no model's types.
     *
     * @throws IllegalArgumentException if a cell is invalid, or columns of different types compare with one input
     */
    public Map<String, ColumnType> namedInputs() {
        if (!feelCells) {
            return Map.of();
        }
        var named = NAMED_INPUTS.get(this);
        if (named == null) {
            named = Collections.unmodifiableMap(findNamedInputs());
            NAMED_INPUTS.put(this, named);
        }
        return named;
    }

    /**
     * The {@link #namedInputs} of the tables asked for them, while those tables are in use; computed outside the lock, so
     * that two threads may both work out the same, equal, inputs.
     */
    private static final Map<DecisionTable, Map<String, ColumnType>> NAMED_INPUTS = Collections.synchronizedMap(new WeakHashMap<>());

    private Map<String, ColumnType> findNamedInputs() {
        var named = new LinkedHashMap<String, ColumnType>();
        var columns = inputs.stream().map(InputColumn::name).collect(Collectors.toSet());
        var read = new LinkedHashMap<String, ColumnType>();
        // a column that is an expression is no name: its text would read as itself
        var scope = FeelExpression.namesScope(columns.stream().filter(CellParser::isName).toList());
        var outputTypes = new HashMap<String, ColumnType>();
        outputs.forEach(output -> outputTypes.put(output.name(), output.type()));
        for (var rule : rules) {
            for (var input : inputs) {
                cellNames(rule, input, columns, scope, named, read);
            }
            rule.output().expressions().forEach((output, text) -> namesRead(text, scope)
                    .forEach(name -> read.putIfAbsent(name, outputTypes.getOrDefault(output, ColumnType.STRING))));
        }
        for (var input : inputs) {
            if (!CellParser.isName(input.name())) {
                namesRead(input.name(), scope).forEach(name -> read.putIfAbsent(name, input.type()));
            }
        }
        read.forEach((name, type) -> {
            if (!columns.contains(name)) {
                named.putIfAbsent(name, type);
            }
        });
        return named;
    }

    /**
     * Adds the names a cell reads: to {@code named} those a simple cell compares with, to {@code read} those a cell in
     * the full FEEL language reads.
     */
    private void cellNames(Praeceptum rule, InputColumn input, Set<String> columns, FeelExpression.Scope scope, Map<String, ColumnType> named,
            Map<String, ColumnType> read) {
        try {
            UnaryTests.names(cell(rule, input)).stream()
                    .filter(name -> !columns.contains(name))
                    .forEach(name -> nameInput(named, name, input.type()));
        } catch (CellSyntaxException e) {
            var tests = FeelUnaryTests.parseCell(rule.input().values().get(input.name()), scope, e);
            tests.names().forEach(name -> read.putIfAbsent(name, input.type()));
        }
    }

    /** The names an expression reads. */
    private static List<String> namesRead(String text, FeelExpression.Scope scope) {
        try {
            return FeelExpression.parse(text, scope).names();
        } catch (ExpressionSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static void nameInput(Map<String, ColumnType> named, String name, ColumnType type) {
        var previous = named.putIfAbsent(name, type);
        if (previous != null && previous != type) {
            throw new IllegalArgumentException("'%s' is compared with both a %s and a %s column"
                    .formatted(name, previous.feelName(), type.feelName()));
        }
    }

    /**
     * @param name          the column name, which is also the input variable it reads
     * @param type          the column type
     * @param allowedValues DMN input value list as unary tests (e.g. {@code "PT","ES"} or {@code [0..120]}), or null
     */
    public record InputColumn(String name, ColumnType type, String allowedValues) {
        public InputColumn {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        public InputColumn(String name, ColumnType type) {
            this(name, type, null);
        }
    }

    /**
     * The output when no rule matches, built from the columns' {@linkplain OutputColumn#defaultValue default
     * values} (DMN's {@code defaultOutputEntry}): DMN 1.5, 10.3.2.10, "if no rules match, if a default output
     * value d is specified, DTI=FEEL(d)". When some column {@linkplain OutputColumn#hasDefault has a default}, it
     * holds every output column, null for a column without one and for one whose default is {@code null} (with
     * several outputs, DMN's default is "a context with entries composed of output component names and output
     * values", Table 46); a table where no column has a default has none, and then gives no output when no rule
     * matches ("else DTI=null").
     */
    public Optional<PraeceptaOutput> defaultOutput() {
        if (outputs.stream().noneMatch(OutputColumn::hasDefault)) {
            return Optional.empty();
        }
        var values = new LinkedHashMap<String, String>();
        outputs.forEach(output -> values.put(output.name(), output.defaultValue()));
        return Optional.of(new PraeceptaOutput(Collections.unmodifiableMap(values)));
    }

    /**
     * @param name         the column name
     * @param type         the type of its values
     * @param values       DMN output value list, highest priority first (used by PRIORITY and OUTPUT_ORDER); may be empty
     * @param defaultValue the value when no rule matches (DMN's {@code defaultOutputEntry}), as plain text like the
     *                     rules' outputs ({@code Declined}, {@code 0}); null if the column has none, or if its
     *                     default is {@code null} (see {@link DecisionTable#defaultOutput})
     * @param hasDefault   whether the column declares a default value: true for a default of {@code null}, which
     *                     {@code defaultValue} cannot tell from none
     * @param typed        whether the column's type is its own: false for an output of a decision model whose type
     *                     neither the output, nor its decision, nor its entries give (entries of several types, or of
     *                     lists or contexts), which is a {@code string} column here, its literal values text, but whose
     *                     entries each give a value of their own type to a {@code DecisionGraph} (DMN 1.5, 10.3.2.10,
     *                     step 3c); its entries are then all expressions ({@link PraeceptaOutput#expressions}), so that
     *                     each keeps its type, and {@code DmnWriter} writes no {@code typeRef} for it
     */
    public record OutputColumn(String name, ColumnType type, List<String> values, String defaultValue, boolean hasDefault,
                               boolean typed) {
        /**
         * A column of the given name, type, output value list and default.
         *
         * @throws IllegalArgumentException if {@code defaultValue} is not null but {@code hasDefault} is false
         */
        public OutputColumn {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            values = List.copyOf(values);
            if (defaultValue != null && !hasDefault) {
                throw new IllegalArgumentException("Output '%s' has the default value '%s' but hasDefault is false".formatted(name, defaultValue));
            }
        }

        /** A column of its own type, of the given name, type, output value list and default. */
        public OutputColumn(String name, ColumnType type, List<String> values, String defaultValue, boolean hasDefault) {
            this(name, type, values, defaultValue, hasDefault, true);
        }

        /** A column with a default value, or none if {@code defaultValue} is null. */
        public OutputColumn(String name, ColumnType type, List<String> values, String defaultValue) {
            this(name, type, values, defaultValue, defaultValue != null);
        }

        /** A column without a default value. */
        public OutputColumn(String name, ColumnType type, List<String> values) {
            this(name, type, values, null);
        }

        /** A column without an output value list or a default value. */
        public OutputColumn(String name, ColumnType type) {
            this(name, type, List.of());
        }
    }
}
