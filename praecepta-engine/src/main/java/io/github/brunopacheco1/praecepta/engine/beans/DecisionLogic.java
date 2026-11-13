/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.Objects;

import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;

/**
 * A decision's logic (DMN 1.5, 6.3.7, {@code decisionLogic}): the expression that gives the decision its value from
 * the values of its information requirements. Praecepta evaluates two kinds of expression, a {@linkplain Table decision
 * table} and a {@linkplain LiteralExpression literal expression} in its expression subset; the other kinds DMN has
 * (boxed contexts, invocations, function definitions, relations, lists, conditionals, iterators, filters) are planned
 * for 0.10 (<a href="https://github.com/brunopacheco1/praecepta/issues/118">#118</a>), and will be further
 * implementations of this interface, so a {@code switch} over a {@code DecisionLogic} should keep a {@code default}
 * branch.
 */
public sealed interface DecisionLogic {

    /**
     * A decision table: its value is the value of a single output, a context of the outputs by name when it has
     * several, and a list of those under a hit policy that returns several results (RULE ORDER, OUTPUT ORDER, COLLECT
     * without an aggregation); null when no rule matches and it declares no default output (DMN 1.5, 10.3.2.10).
     *
     * <p>Its value is bound to the type its decision declares as a literal expression's is (DMN 1.5, 10.3.2.9.4): a
     * single result to a list as a list of one, a list of one result to a single value as that result, a date to a date
     * and time at UTC midnight, and a value of a type that does not conform to the declared type as null.
     *
     * @param table the table, named after its decision
     * @param type  the type the model declares for the decision's value (its variable's {@code typeRef}), or
     *              {@link FeelType#ANY} if it declares none
     */
    record Table(DecisionTable table, FeelType type) implements DecisionLogic {
        public Table {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(type, "type");
        }

        /** A decision table whose decision's type the model does not declare: its value is of the type its outputs give. */
        public Table(DecisionTable table) {
            this(table, FeelType.ANY);
        }
    }

    /**
     * A literal expression in Praecepta's expression subset, the one decision tables' output entries take
     * ({@link OutputExpression}): literals (strings, numbers, dates, times, date-times and durations), names of the
     * decision's information requirements, with a path into a context ({@code Pricing.discount}), joined with
     * {@code +}, {@code -}, {@code *}, {@code /}, {@code **}, unary minus and parentheses, as in
     * {@code "Hello " + Full Name} or {@code (Pricing.price - Pricing.discount) * 1.2}. The full FEEL language is planned
     * for 0.8 (<a href="https://github.com/brunopacheco1/praecepta/issues/116">#116</a>), and its built-in functions for
     * 0.9 (<a href="https://github.com/brunopacheco1/praecepta/issues/117">#117</a>).
     *
     * @param text the expression, as written
     * @param type the type the model declares for the decision's value (its variable's {@code typeRef}), or
     *             {@link FeelType#ANY} if it declares none. The value is bound to it as DMN 1.5, 10.3.2.9.4, binds a
     *             decision's value to its variable: a date to a date and time as UTC midnight, a single value to a list
     *             as a list of one, a list of one value to a single value as that value, and a value of a type that does
     *             not conform as null
     */
    record LiteralExpression(String text, FeelType type) implements DecisionLogic {

        /**
         * A literal expression, checked to be in Praecepta's expression subset.
         *
         * @throws IllegalArgumentException if it is not, naming what is not supported and the release that plans it
         */
        public LiteralExpression {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(type, "type");
            parse(text);
        }

        /** A literal expression whose type the model does not declare. */
        public LiteralExpression(String text) {
            this(text, FeelType.ANY);
        }

        /**
         * The parsed expression, parsed again on each call. Its form is that of Praecepta's expression subset, which
         * the full FEEL language replaces in 0.8 (#116): this method is meant for Praecepta's own modules, and its
         * return type will change then.
         *
         * @throws IllegalArgumentException never, as the text was checked when the expression was made
         */
        public OutputExpression expression() {
            return parse(text);
        }

        private static OutputExpression parse(String text) {
            try {
                return OutputExpression.parse(text);
            } catch (ExpressionSyntaxException e) {
                var planned = e.getMessage().contains("function call")
                        ? "built-in functions are planned for 0.9 (#117), and the full FEEL language for 0.8 (#116)"
                        : "the full FEEL language is planned for 0.8 (#116), and its built-in functions for 0.9 (#117)";
                throw new IllegalArgumentException(("literal expression '%s' is not in Praecepta's expression subset "
                        + "(literals and names joined with +, -, *, / and **): %s; %s").formatted(text.strip(), e.getMessage(), planned), e);
            }
        }
    }
}
