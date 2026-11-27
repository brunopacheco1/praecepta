/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A decision's logic (DMN 1.5, 6.3.7, {@code decisionLogic}), and any expression within it: the expression that gives a
 * value from the names in scope, a decision's information requirements and the business knowledge it requires. It is a
 * {@linkplain Table decision table}, a {@linkplain LiteralExpression literal expression} in FEEL, or one of the boxed
 * expressions of DMN 1.5, chapter 10:
 * a {@linkplain Context context}, an {@linkplain Invocation invocation}, a {@linkplain FunctionDefinition function
 * definition}, a {@linkplain Relation relation}, a {@linkplain BoxedList list}, a {@linkplain Conditional conditional},
 * an {@linkplain Iteration iteration} ({@code for}, {@code some}, {@code every}) or a {@linkplain Filter filter}, whose
 * parts are expressions in turn. Each is evaluated as the FEEL expression it boxes ({@link FeelExpression#compose}).
 *
 * <p>Every kind has a {@linkplain #type() type}, the one its model declares for it, or {@link FeelType#ANY}. A
 * decision's logic has its decision's, to which the graph binds its value (DMN 1.5, 10.3.2.9.4); a part of a boxed
 * expression has its own, to which its value is bound the same way where it is evaluated: a context entry's value to
 * its variable's type, an argument to its parameter's, and so on. Later releases may add kinds, so a {@code switch} over
 * a {@code DecisionLogic} should keep a {@code default} branch.
 */
public sealed interface DecisionLogic {

    /** The type its model declares for its value, or {@link FeelType#ANY} if it declares none. */
    FeelType type();

    /**
     * The name of the DMN element that holds it: {@code decisionTable}, {@code literalExpression}, {@code context},
     * {@code invocation}, {@code functionDefinition}, {@code relation}, {@code list}, {@code conditional}, {@code for},
     * {@code some}, {@code every} or {@code filter}; empty for {@link NoLogic}, which no element holds.
     */
    default String element() {
        return switch (this) {
            case Table table -> "decisionTable";
            case LiteralExpression literal -> "literalExpression";
            case Context context -> "context";
            case Invocation invocation -> "invocation";
            case FunctionDefinition function -> "functionDefinition";
            case Relation relation -> "relation";
            case BoxedList list -> "list";
            case Conditional conditional -> "conditional";
            case Iteration iteration -> iteration.kind().name().toLowerCase(Locale.ROOT);
            case Filter filter -> "filter";
            case NoLogic none -> "";
        };
    }

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
     * the business knowledge models it requires, and the graph parses it with them in scope, as FEEL's names may hold
     * spaces, keywords and numbers ({@code decision A 2.1}). FEEL's built-in functions are evaluated; a decision that
     * defines an external function is reported as not evaluated, by design.
     *
     * <p>Whether it is FEEL depends on the names in scope where it is, so it is checked there: {@code DmnReader} rejects a
     * model whose expressions are not FEEL with the names their elements may read, and {@code DecisionGraph} reports one
     * built in code ({@link io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind#INVALID_EXPRESSION}). It is
     * not checked when it is made.
     *
     * @param text the expression, as written
     * @param type the type the model declares for the decision's value (its variable's {@code typeRef}), or
     *             {@link FeelType#ANY} if it declares none. The value is bound to it as DMN 1.5, 10.3.2.9.4, binds a
     *             decision's value to its variable: a date to a date and time as UTC midnight, a single value to a list
     *             as a list of one, a list of one value to a single value as that value, and a value of a type that does
     *             not conform as null
     */
    record LiteralExpression(String text, FeelType type) implements DecisionLogic {

        /** A literal expression. */
        public LiteralExpression {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(type, "type");
        }

        /** A literal expression whose type the model does not declare. */
        public LiteralExpression(String text) {
            this(text, FeelType.ANY);
        }

        /**
         * The expression as its syntax alone reads it, parsed again on each call: for checking what it uses
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
         * @throws IllegalArgumentException if it is not a FEEL expression without those names in scope, saying why
         */
        public FeelExpression expression() {
            try {
                return FeelExpression.parse(text);
            } catch (ExpressionSyntaxException e) {
                throw new IllegalArgumentException("literal expression '%s' is not a valid FEEL expression: %s"
                        .formatted(text.strip(), e.getMessage()), e);
            }
        }
    }

    /**
     * A boxed context (DMN 1.5, 10.2.1 and 10.3.2.6): entries, each in the scope of those before it and of itself (so a
     * function an entry defines may invoke itself), and an optional final result. Its value is the result's, evaluated
     * in the scope of every entry; without one, the context of the entries' values by name.
     *
     * @param entries the entries, in order, of distinct names
     * @param result  the final result, or null for none
     * @param type    the type the model declares for its value
     */
    record Context(List<ContextEntry> entries, DecisionLogic result, FeelType type) implements DecisionLogic {
        public Context {
            entries = List.copyOf(entries);
            Objects.requireNonNull(type, "type");
            var names = new HashSet<String>();
            for (var entry : entries) {
                if (!names.add(entry.name())) {
                    throw new IllegalArgumentException("the context has two entries named '%s' (DMN 1.5, 10.3.2.6)".formatted(entry.name()));
                }
            }
        }
    }

    /**
     * An entry of a boxed context: its variable's name, and the expression whose value it holds, bound to the type of
     * the expression ({@link DecisionLogic#type()}), which is its variable's.
     */
    record ContextEntry(String name, DecisionLogic value) {
        public ContextEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * A boxed invocation (DMN 1.5, 10.2.4 and 10.3.2.13): the function a FEEL expression names, usually a business
     * knowledge model ({@code Installment calculation}), invoked with its parameters bound by name. A parameter no binding
     * names is null; a binding without an expression binds null.
     *
     * @param function the FEEL expression that gives the function
     * @param bindings the bindings, of distinct parameters
     * @param type     the type the model declares for its value
     */
    record Invocation(String function, List<Binding> bindings, FeelType type) implements DecisionLogic {
        public Invocation {
            Objects.requireNonNull(function, "function");
            bindings = List.copyOf(bindings);
            Objects.requireNonNull(type, "type");
            var names = new HashSet<String>();
            for (var binding : bindings) {
                if (!names.add(binding.parameter())) {
                    throw new IllegalArgumentException("the invocation binds parameter '%s' twice (DMN 1.5, 10.2.4)".formatted(binding.parameter()));
                }
            }
        }
    }

    /**
     * A binding of a boxed invocation: the parameter it names and the expression whose value it is given; null for
     * none, which gives null.
     */
    record Binding(String parameter, DecisionLogic value) {
        public Binding {
            Objects.requireNonNull(parameter, "parameter");
        }
    }

    /**
     * A boxed function definition (DMN 1.5, 10.2.3 and 10.3.2.13), in FEEL: its value is a function of its parameters
     * whose body is evaluated in the scope it is defined in, with the parameters bound to the arguments, each to its
     * declared type. A business knowledge model's logic is one. Functions in Java or PMML are external functions, which
     * Praecepta does not evaluate, by design: they are rejected when read.
     *
     * @param parameters its formal parameters, of distinct names
     * @param body       its body
     * @param type       the type the model declares for its value (a function type), or any type
     */
    record FunctionDefinition(List<Parameter> parameters, DecisionLogic body, FeelType type) implements DecisionLogic {
        public FunctionDefinition {
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(type, "type");
            var names = new HashSet<String>();
            for (var parameter : parameters) {
                if (!names.add(parameter.name())) {
                    throw new IllegalArgumentException("the function has two parameters named '%s'".formatted(parameter.name()));
                }
            }
        }
    }

    /** A formal parameter of a function: its name and declared type ({@link FeelType#ANY} for none). */
    record Parameter(String name, FeelType type) {
        public Parameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * A relation (DMN 1.5, 10.2.6): a list of contexts, one a row, each with the relation's columns as keys.
     *
     * @param columns the columns, each a name and the type each of its cells is bound to
     * @param rows    the rows, each with an expression a column
     * @param type    the type the model declares for its value
     */
    record Relation(List<Parameter> columns, List<List<DecisionLogic>> rows, FeelType type) implements DecisionLogic {
        public Relation {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            Objects.requireNonNull(type, "type");
            var names = new HashSet<String>();
            for (var column : columns) {
                if (!names.add(column.name())) {
                    throw new IllegalArgumentException("the relation has two columns named '%s'".formatted(column.name()));
                }
            }
            for (var row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException("a row of the relation has %d cells for %d columns".formatted(row.size(),
                            columns.size()));
                }
            }
        }
    }

    /**
     * A boxed list (DMN 1.5, 10.2.5): the list of its elements' values.
     *
     * @param elements the elements' expressions
     * @param type     the type the model declares for its value
     */
    record BoxedList(List<DecisionLogic> elements, FeelType type) implements DecisionLogic {
        public BoxedList {
            elements = List.copyOf(elements);
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * A boxed conditional (DMN 1.5, 10.2.7): FEEL's {@code if c then a else b}, where a condition that is not true
     * gives the {@code else} value.
     */
    record Conditional(DecisionLogic condition, DecisionLogic then, DecisionLogic otherwise, FeelType type) implements DecisionLogic {
        public Conditional {
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(then, "then");
            Objects.requireNonNull(otherwise, "otherwise");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * A boxed iterator (DMN 1.5, 10.2.8): FEEL's {@code for v in domain return body}, {@code some v in domain satisfies
     * body} or {@code every v in domain satisfies body}.
     *
     * @param kind     which of the three it is
     * @param variable the iterator variable, in scope in the body
     * @param domain   what it iterates over
     * @param body     the {@code return} or {@code satisfies} expression
     * @param type     the type the model declares for its value
     */
    record Iteration(Kind kind, String variable, DecisionLogic domain, DecisionLogic body, FeelType type) implements DecisionLogic {
        public Iteration {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(variable, "variable");
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(type, "type");
        }

        /** The kinds of iterator. */
        public enum Kind {
            /** {@code for}: the list of the body's values. */
            FOR,
            /** {@code some}: whether the body is true for some element. */
            SOME,
            /** {@code every}: whether the body is true for every element. */
            EVERY
        }
    }

    /**
     * A boxed filter (DMN 1.5, 10.2.9): FEEL's {@code list[match]}, the elements of the list for which the match,
     * with {@code item} and an element's entries in scope, is true.
     */
    record Filter(DecisionLogic list, DecisionLogic match, FeelType type) implements DecisionLogic {
        public Filter {
            Objects.requireNonNull(list, "list");
            Objects.requireNonNull(match, "match");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * No logic (DMN 1.5, 6.3.7: a decision's logic is optional), as for a decision a person makes: the decision's value is
     * not computed but given, as a decision service takes the value of an input decision as its argument. A graph does
     * not evaluate such a decision, nor what requires it outside a decision service that takes it so.
     *
     * @param type the type the model declares for the decision's value, or {@link FeelType#ANY}
     */
    record NoLogic(FeelType type) implements DecisionLogic {
        public NoLogic {
            Objects.requireNonNull(type, "type");
        }
    }
}
