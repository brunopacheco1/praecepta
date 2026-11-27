/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.GraphAnalysis.Resolution;
import io.github.brunopacheco1.praecepta.engine.TableReads.ColumnRead;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * The decisions of a {@link DecisionGraph}, ready to evaluate: built once, with everything parsed and resolved, so an
 * evaluation only reads values from the array of the graph's values (one slot per input data and decision) and
 * computes.
 */
final class GraphNodes {

    private GraphNodes() {
    }

    /** A decision's logic, evaluated from the values of the graph's slots. */
    interface Node {

        /**
         * The decision's value; the slots of the decisions and input data it requires hold theirs.
         *
         * @param errors receives the errors FEEL reports on the way (DMN 1.5, 10.3.2.15); null to ignore them
         */
        Object evaluate(Object[] values, Consumer<String> errors);

        /** The validation report of its decision table; empty for other logic. */
        ValidationReport report();
    }

    /** A value a reader reads from the slots: the value at a path in a requirement's value. */
    private static Function<Object[], Object> reader(Resolution resolution) {
        int slot = resolution.slot();
        var path = resolution.path();
        return path.length == 0 ? values -> values[slot] : values -> GraphValues.read(values[slot], path);
    }

    // ---- decision tables ---------------------------------------------------------------------------

    /** A decision table, evaluated by a {@link PraeceptaEngine} whose inputs are the graph's slots. */
    static final class TableNode implements Node {

        private final PraeceptaEngine<Object[], Object> engine;
        /** Whether the table's value is a list: a hit policy with several results, and no aggregation. */
        private final boolean listResult;
        private final GraphValues.Binding binding;
        private final FeelType declared;

        /**
         * @param analysis the graph's analysis, which says, for decision {@code d}: what the table reads (columns, the
         *                 inputs its cells compare with, and the names its FEEL reads), where each name it reads is among
         *                 the graph's slots (a name it cannot read is missing), the inputs its cells compare with whose
         *                 values are lists, though the table reads single values, its columns that read a property of a
         *                 date, time or duration, and how its value is bound to its decision
         * @param declared the type its decision declares
         * @param log      whether the engine logs its validation report
         * @throws InvalidDecisionTableException if the table cannot be built into an engine
         */
        TableNode(DecisionTable table, GraphAnalysis analysis, int d, FeelType declared, boolean log) {
            this(table, analysis.tableReads[d], analysis.reads.get(d), analysis.propertyColumns.get(d), analysis.listedNamedInputs.get(d),
                    analysis.bindings[d], declared, analysis.model.types(), log);
        }

        /**
         * @param reads      what the table reads: its columns, the inputs its cells compare with, and the names its FEEL reads
         * @param names      where each name it reads is, among the slots it is given (a name it cannot read is missing)
         * @param properties its columns that read a property of a date, time or duration
         * @param listed     the inputs its cells compare with whose values are lists, though the table reads single values
         * @param binding    how its value is bound to its declared type
         * @param types      the types the model defines, which its FEEL may name
         * @throws InvalidDecisionTableException if the table cannot be built into an engine
         */
        TableNode(DecisionTable table, TableReads reads, Map<String, Resolution> names,
                Map<String, GraphAnalysis.PropertyColumn> properties, Set<String> listed, GraphValues.Binding binding, FeelType declared,
                Map<String, FeelType> types, boolean log) {
            listResult = !table.hitPolicy().isSingleHit() && table.aggregation() == null;
            this.binding = binding;
            this.declared = declared;
            var lists = new HashSet<>(table.listInputs());
            lists.addAll(listed);
            var read = lists.equals(table.listInputs()) ? table : new DecisionTable(table.name(), table.hitPolicy(), table.aggregation(),
                    table.inputs(), table.outputs(), table.rules(), table.feelCells(), lists);
            try {
                if (table.aggregation() != null) {
                    table.aggregation().check(table.outputs());
                }
                var builder = PraeceptaEngine.<Object[], Object>builder()
                        .table(read)
                        .output(new TableOutputs(table.outputs()))
                        .types(types)
                        .parsed(reads.parsed())
                        .logValidationReport(log);
                bindColumns(builder, reads, names, properties, lists);
                bindExpressions(builder, reads, names);
                engine = builder.build();
            } catch (RuntimeException e) {
                throw new InvalidDecisionTableException("Decision '%s': %s".formatted(table.name(), e.getMessage()));
            }
        }

        /**
         * Binds the columns that name what they read and the inputs simple cells compare with, each to a getter of its
         * slot and path, or, for a path to a property of a date, time or duration, to the property as FEEL reads it.
         */
        private static void bindColumns(PraeceptaEngine.Builder<Object[], Object> builder, TableReads reads,
                Map<String, Resolution> names, Map<String, GraphAnalysis.PropertyColumn> properties, Set<String> lists) {
            for (var column : reads.columns()) {
                var list = lists.contains(column.name());
                var property = properties.get(column.name());
                builder.valueInput(column.name(), column.type(), list, property == null
                        ? getter(names.get(column.name()), column, list)
                        : computed(property.expression(), column, Map.of(property.root(), new Resolution(property.slot(), new String[0], null))));
            }
        }

        /**
         * Binds what the table's FEEL reads: the names its cells, input expressions and output entries read beside its
         * columns, given as their values are. The columns its input expressions compute, the engine computes from them.
         */
        private static void bindExpressions(PraeceptaEngine.Builder<Object[], Object> builder, TableReads reads,
                Map<String, Resolution> names) {
            for (var name : reads.expressions()) {
                var resolution = names.get(name);
                if (resolution == null) {
                    builder.expressionInput(name, FeelType.ANY, values -> null); // no requirement provides it: reported
                } else {
                    builder.expressionInput(name, resolution.type() == null ? FeelType.ANY : resolution.type(), reader(resolution));
                }
            }
        }

        /**
         * The getter of a column whose input expression is an expression ({@code Age * 12}): its value, from the values
         * of the names it reads, made a value of the column's type as a column's value of no declared type is, or
         * rejected.
         */
        private static Function<Object[], Object> computed(FeelExpression expression, ColumnRead column, Map<String, Resolution> names) {
            var readers = new HashMap<String, Function<Object[], Object>>();
            names.forEach((name, resolution) -> readers.put(name, reader(resolution)));
            return values -> {
                var value = expression.evaluate(name -> {
                    var reader = readers.get(name);
                    return reader == null ? null : reader.apply(values);
                });
                return column.list() ? asList(value, column, true) : checked(value, column);
            };
        }

        /**
         * The getter of a column: the value at its path, which a column of lists takes as a list of one when it is a
         * single value (DMN 1.5, 10.3.2.9.4, "to singleton list"), and which, if the model does not declare its type, is
         * made a value of the column's type as a declared input data's is ({@link GraphValues#normalise}: text, an
         * {@code Integer}, an {@code OffsetDateTime}...), or rejected.
         */
        private static Function<Object[], Object> getter(Resolution resolution, ColumnRead column, boolean list) {
            if (resolution == null) {
                return values -> null; // a name none of the decision's requirements provides: it is never evaluated
            }
            var read = reader(resolution);
            boolean check = unchecked(resolution.type());
            if (list) {
                return values -> asList(read.apply(values), column, check);
            }
            return check ? values -> checked(read.apply(values), column) : read;
        }

        /** Whether values of this type may be of any type, so a column checks what it is given. */
        private static boolean unchecked(FeelType type) {
            return type instanceof FeelType.Any || type instanceof FeelType.ListOf(FeelType.Any any);
        }

        private static Object asList(Object value, ColumnRead column, boolean check) {
            if (value == null) {
                return null;
            }
            if (value instanceof List<?> list) {
                if (check) {
                    var elements = new ArrayList<>(list.size());
                    list.forEach(element -> elements.add(checked(element, column)));
                    return Collections.unmodifiableList(elements);
                }
                return list;
            }
            return List.of(check ? checked(value, column) : value);
        }

        private static Object checked(Object value, ColumnRead column) {
            return GraphValues.normalise(value, FeelType.of(column.type()), column.name());
        }

        @Override
        public Object evaluate(Object[] values, Consumer<String> errors) {
            var results = engine.evaluate(values, errors);
            if (results.isEmpty()) {
                return null; // DMN 1.5, 10.3.2.10, step 2: no rule matches and no default output
            }
            if (listResult && engine.isDefault(results)) {
                // DMN 1.5, 10.3.2.10, step 2: the default output is a single value, whatever the hit policy, which binds
                // as 10.3.2.9.4 has it: to a decision typed by a list as a list of one (the DMN TCK's
                // 0109-ruleOrder-hitpolicy), and to one of no declared type as it is (docs/DMN-CONFORMANCE.md, case 14)
                var single = value(results.getFirst());
                if (binding == GraphValues.Binding.NULL) {
                    return null;
                }
                return declared instanceof FeelType.Any ? single : GraphValues.conform(single, declared, 0);
            }
            if (listResult) {
                var list = new ArrayList<>(results.size());
                results.forEach(result -> list.add(value(result)));
                return GraphValues.bind(Collections.unmodifiableList(list), binding, declared);
            }
            return GraphValues.bind(value(results.getFirst()), binding, declared);
        }

        private static Object value(Object result) {
            return result instanceof Missing ? null : result;
        }

        @Override
        public ValidationReport report() {
            return engine.validationReport();
        }
    }

    /**
     * A decision table within a boxed expression (a business knowledge model's body, a context entry): evaluated by a
     * {@link PraeceptaEngine} as a decision's table is, from the values of the names in scope where it is, which the FEEL
     * around it gives. A name its enclosing constructs bind (a parameter, an earlier entry) is read from that scope, with
     * its path, and any other name as the scope outside resolves it, requirement and path; values are checked against
     * their columns' types, as those of no declared type are. It keeps its validation report.
     */
    static final class EmbeddedTable implements FeelExpression.Embedded {
        private final TableNode node;
        /** What each of the table's slots is read as: a name in scope, whose value is at the slot's paths. */
        private final String[] keys;
        private final List<String> names;
        private final FeelType type;

        /**
         * @throws InvalidDecisionTableException if a cell or an expression is invalid, or the table cannot be built
         */
        EmbeddedTable(FeelExpression.TableSite site, boolean log) {
            var table = site.table().table();
            var scope = TableReads.scope(new LinkedHashSet<>(site.names()), table, site.types());
            var reads = TableReads.of(table.name(), table, scope);
            var locals = site.locals();
            var keyList = new ArrayList<String>();
            var slots = new HashMap<String, Integer>();
            var resolutions = new LinkedHashMap<String, Resolution>();
            for (var name : reads.names()) {
                var local = DecisionModel.elementOf(name, locals::contains);
                var key = local == null ? name : local;
                var path = local == null ? new String[0] : Resolution.pathOf(name, local);
                int slot = slots.computeIfAbsent(key, k -> {
                    keyList.add(k);
                    return keyList.size() - 1;
                });
                resolutions.put(name, new Resolution(slot, path, FeelType.ANY));
            }
            keys = keyList.toArray(String[]::new);
            names = List.copyOf(reads.names());
            var declared = site.table().type();
            node = new TableNode(table, reads, resolutions, Map.of(), Set.of(), GraphValues.Binding.AS_IS, declared, site.types(), log);
            type = declared instanceof FeelType.Any ? GraphTypes.tableType(table) : declared;
        }

        @Override
        public List<String> names() {
            return names;
        }

        @Override
        public FeelType type() {
            return type;
        }

        @Override
        public Object evaluate(Function<String, Object> values, Consumer<String> errors) {
            var slots = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                slots[i] = values.apply(keys[i]);
            }
            return node.evaluate(slots, errors);
        }

        /** The table's validation report. */
        ValidationReport report() {
            return node.report();
        }
    }

    /** A null value, which an output strategy cannot return. */
    private enum Missing {
        /** A missing output: a {@code null} entry, an expression with a missing operand, or an aggregate of a missing output. */
        NULL,
        /** A MIN or MAX over values FEEL does not order (times in two zones), which is null (DMN 1.5, 10.3.2.16). */
        INCOMPARABLE
    }

    /**
     * A table's outputs as the decision's value: its one output's value, or a context of its outputs' values by name,
     * each of its output's type ({@link GraphValues#outputValue}). The aggregations combine the one output's values as
     * the framework integrations' map-based engine does: SUM with FEEL's 34 digits, MIN and MAX in FEEL's order (null for
     * values that do not compare), COUNT as a number.
     */
    private static final class TableOutputs implements ValueOutputs<Object> {
        private final List<OutputColumn> columns;

        TableOutputs(List<OutputColumn> columns) {
            this.columns = List.copyOf(columns);
        }

        @Override
        public Object transform(PraeceptaOutput output) {
            return transform(output, Map.of());
        }

        /**
         * The outputs as the decision's value: the values the rule computes as they are (a list, a context, or a value of
         * an output of no declared type, which keeps its own, DMN 1.5, 10.3.2.10, step 3c), the others from their text.
         */
        @Override
        public Object transform(PraeceptaOutput output, Map<String, Object> values) {
            Object value;
            if (columns.size() == 1) {
                value = value(columns.getFirst(), output, values);
            } else {
                var context = new LinkedHashMap<String, Object>();
                columns.forEach(column -> context.put(column.name(), value(column, output, values)));
                value = Collections.unmodifiableMap(context);
            }
            return value == null ? Missing.NULL : value;
        }

        private static Object value(OutputColumn column, PraeceptaOutput output, Map<String, Object> values) {
            return values.containsKey(column.name())
                    ? values.get(column.name())
                    : GraphValues.outputValue(output.values().get(column.name()), column.type());
        }

        @Override
        public Object sum(Object a, Object b) {
            return ((BigDecimal) a).add((BigDecimal) b, MathContext.DECIMAL128);
        }

        @Override
        public Object min(Object a, Object b) {
            return extreme(a, b, -1);
        }

        @Override
        public Object max(Object a, Object b) {
            return extreme(a, b, 1);
        }

        /** The smaller ({@code sign} -1) or larger (1) value; null, for good, once two values do not compare. */
        private static Object extreme(Object a, Object b, int sign) {
            if (a == Missing.INCOMPARABLE) {
                return a;
            }
            if (!FeelTemporals.comparable(a, b)) {
                return Missing.INCOMPARABLE;
            }
            return Integer.signum(FeelTemporals.compare(a, b)) == -sign ? b : a;
        }

        @Override
        public Object count(int matches) {
            return BigDecimal.valueOf(matches);
        }
    }

    // ---- literal expressions -----------------------------------------------------------------------

    /** A literal expression, parsed once, whose names read the graph's slots. */
    static final class LiteralNode implements Node {
        private final FeelExpression expression;
        private final Map<String, Function<Object[], Object>> readers = new HashMap<>();
        private final GraphValues.Binding binding;
        private final FeelType declared;

        /**
         * @param names    where each name it reads is, among the graph's slots
         * @param binding  how its value is bound to its decision
         * @param declared the type its decision declares
         */
        LiteralNode(FeelExpression expression, Map<String, Resolution> names, GraphValues.Binding binding, FeelType declared) {
            this.expression = expression;
            names.forEach((name, resolution) -> readers.put(name, reader(resolution)));
            this.binding = binding;
            this.declared = declared;
        }

        @Override
        public Object evaluate(Object[] values, Consumer<String> errors) {
            if (binding == GraphValues.Binding.NULL && errors == null) {
                return null; // DMN 1.5 defines its value as null whatever its inputs
            }
            Function<String, Object> inputs = name -> {
                var reader = readers.get(name);
                return reader == null ? null : reader.apply(values);
            };
            var value = GraphValues.literalValue(expression.evaluate(inputs, errors));
            var bound = GraphValues.bind(value, binding, declared);
            if (bound == null && value != null && errors != null) {
                errors.accept("its value is not a %s, the type its decision declares (DMN 1.5, 10.3.2.9.4)".formatted(declared.feelName()));
            }
            return bound;
        }

        @Override
        public ValidationReport report() {
            return new ValidationReport(List.of());
        }
    }
}
