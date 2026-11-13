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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.GraphAnalysis.ColumnRead;
import io.github.brunopacheco1.praecepta.engine.GraphAnalysis.Resolution;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionTypeException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;

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

        /** The decision's value; the slots of the decisions and input data it requires hold theirs. */
        Object evaluate(Object[] values);

        /** The validation report of its decision table; empty for other logic. */
        ValidationReport report();
    }

    /** A value a reader reads from the slots: the value at a path in a requirement's value. */
    private static Function<Object[], Object> reader(Resolution resolution) {
        int slot = resolution.slot();
        var path = resolution.path();
        return path.length == 0 ? values -> values[slot] : values -> GraphValues.read(values[slot], path);
    }

    /** Whether values of this type may be of any type, so a column checks what it is given. */
    private static boolean unchecked(FeelType type) {
        return type instanceof FeelType.Any || type instanceof FeelType.ListOf(FeelType.Any any);
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
         * @param reads    what the table reads, columns and the inputs its cells compare with
         * @param names    where each name it reads is, among the graph's slots; a name it cannot read is missing
         * @param listed   the inputs its cells compare with whose values are lists, though the table reads single values
         * @param binding  how its value is bound to its decision
         * @param declared the type its decision declares
         * @param log      whether the engine logs its validation report
         * @throws InvalidDecisionTableException if the table cannot be built into an engine
         */
        TableNode(DecisionTable table, List<ColumnRead> reads, Map<String, Resolution> names, Set<String> listed,
                GraphValues.Binding binding, FeelType declared, boolean log) {
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
                        .logValidationReport(log);
                for (var column : reads) {
                    var list = lists.contains(column.name());
                    builder.valueInput(column.name(), column.type(), list, getter(names.get(column.name()), column, list));
                }
                engine = builder.build();
            } catch (RuntimeException e) {
                throw new InvalidDecisionTableException("Decision '%s': %s".formatted(table.name(), e.getMessage()));
            }
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
        public Object evaluate(Object[] values) {
            var results = engine.evaluate(values);
            if (results.isEmpty()) {
                return null; // DMN 1.5, 10.3.2.10, step 2: no rule matches and no default output
            }
            if (listResult) {
                // a default output, under a hit policy with several results, is given as a list of one, which binds as
                // 10.3.2.9.4 has it to a decision typed by a list (as the DMN TCK's 0109-ruleOrder-hitpolicy has it); to
                // an untyped decision, DMN 1.5 gives the single value (docs/DMN-CONFORMANCE.md, case 14)
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
    private static final class TableOutputs implements OutputStrategy<Object> {
        private final List<OutputColumn> columns;

        TableOutputs(List<OutputColumn> columns) {
            this.columns = List.copyOf(columns);
        }

        @Override
        public Object transform(PraeceptaOutput output) {
            Object value;
            if (columns.size() == 1) {
                var column = columns.getFirst();
                value = GraphValues.outputValue(output.values().get(column.name()), column.type());
            } else {
                var context = new LinkedHashMap<String, Object>();
                columns.forEach(column -> context.put(column.name(), GraphValues.outputValue(output.values().get(column.name()), column.type())));
                value = Collections.unmodifiableMap(context);
            }
            return value == null ? Missing.NULL : value;
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
        private final OutputExpression expression;
        private final Map<String, Function<Object[], Object>> readers = new HashMap<>();
        private final GraphValues.Binding binding;
        private final FeelType declared;
        private final boolean dynamic;

        /**
         * @param names    where each name it reads is, among the graph's slots
         * @param binding  how its value is bound to its decision
         * @param declared the type its decision declares
         * @param dynamic  whether a name it reads has a type known only when it is evaluated, so its operands are typed then
         */
        LiteralNode(OutputExpression expression, Map<String, Resolution> names, GraphValues.Binding binding, FeelType declared, boolean dynamic) {
            this.expression = expression;
            names.forEach((name, resolution) -> readers.put(name, reader(resolution)));
            this.binding = binding;
            this.declared = declared;
            this.dynamic = dynamic;
        }

        @Override
        public Object evaluate(Object[] values) {
            if (binding == GraphValues.Binding.NULL) {
                return null; // DMN 1.5 defines its value as null whatever its inputs
            }
            Function<String, Object> inputs = name -> readers.get(name).apply(values);
            if (dynamic && !combines(inputs)) {
                return null; // operands FEEL's arithmetic does not combine, or a missing one (DMN 1.5, 10.3.2.15)
            }
            return GraphValues.bind(GraphValues.literalValue(expression.evaluate(inputs)), binding, declared);
        }

        /** Whether the values the expression reads combine as FEEL's arithmetic has them, typed as they are now. */
        private boolean combines(Function<String, Object> inputs) {
            var types = new HashMap<String, ColumnType>();
            for (var name : readers.keySet()) {
                var type = GraphValues.arithmeticType(inputs.apply(name));
                if (type == null) {
                    return false;
                }
                types.put(name, type);
            }
            try {
                expression.type(types::get);
                return true;
            } catch (ExpressionTypeException e) {
                return false;
            }
        }

        @Override
        public ValidationReport report() {
            return new ValidationReport(List.of());
        }
    }
}
