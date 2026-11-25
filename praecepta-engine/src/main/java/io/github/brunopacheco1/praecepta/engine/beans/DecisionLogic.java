/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.Objects;

import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A decision's logic (DMN 1.5, 6.3.7, {@code decisionLogic}): the expression that gives the decision its value from
 * the values of its information requirements. Praecepta evaluates two kinds of expression, a {@linkplain Table decision
 * table} and a {@linkplain LiteralExpression literal expression} in FEEL; the other kinds DMN has
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
     * A literal expression (DMN 1.5, 10.2): a FEEL expression ({@link FeelExpression}) whose value is the decision's, such
     * as {@code "Hello " + Full Name}, {@code if Applicant.age >= 18 then "adult" else "minor"} or
     * {@code Orders[amount > 100].customer}. The names it reads are those of the decision's information requirements, and
     * the graph parses it with them in scope, as FEEL's names may hold spaces and keywords. FEEL's built-in functions are
     * evaluated (since 0.9, <a href="https://github.com/brunopacheco1/praecepta/issues/117">#117</a>); a decision that
     * defines an external function, planned for 0.11, is reported as not evaluated yet.
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
         * A literal expression, checked to be FEEL.
         *
         * @throws IllegalArgumentException if it is not a FEEL expression, saying why
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
         * The expression as its syntax alone reads it, parsed again on each call: for checking its syntax and what it uses
         * ({@link FeelExpression#unsupported()}), not for the names it reads, nor for evaluating it.
         *
         * <p>It is parsed with no names in scope but those it binds itself, and no model's types: every other name is
         * read as words up to a keyword or an operator, and every type name it does not know as any type. A
         * {@code DecisionGraph} parses it with the names of its decision's requirements in scope, and the model's item
         * definitions, which this cannot know ({@link FeelExpression#parse(String, java.util.Collection, java.util.Map)}),
         * so its {@link FeelExpression#names()} may differ from those the graph reads: a requirement named
         * {@code Income - Tax} is one name to the graph but the subtraction of {@code Tax} from {@code Income} here, and
         * a type name the model does not define is an error to the graph but not here.
         *
         * @throws IllegalArgumentException never, as the text was checked when the expression was made
         */
        public FeelExpression expression() {
            return parse(text);
        }

        private static FeelExpression parse(String text) {
            try {
                return FeelExpression.parse(text);
            } catch (ExpressionSyntaxException e) {
                throw new IllegalArgumentException("literal expression '%s' is not a valid FEEL expression: %s"
                        .formatted(text.strip(), e.getMessage()), e);
            }
        }
    }
}
