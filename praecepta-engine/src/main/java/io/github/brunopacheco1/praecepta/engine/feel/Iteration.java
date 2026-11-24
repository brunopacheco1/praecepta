/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.Predicate;

/**
 * The iteration contexts of {@code for}, {@code some} and {@code every} (DMN 1.5, 10.3.2.14): each variable takes
 * each element of its list in turn, the later ones varying fastest, a later list being evaluated in the scope of the
 * earlier variables. It loops with arrays of its own rather than recursing, however many variables there are, and
 * counts every iteration as a step ({@link Env#MAX_STEPS}). A range of numbers or dates ({@code 1..10}) is a list
 * that computes its elements as they are read, which costs a step an element when it is made.
 */
final class Iteration {

    private Iteration() {
    }

    /**
     * Visits every combination of the variables' values, in order, in a scope that binds them.
     *
     * @param visit given the scope of one combination; false to stop
     * @return false if an iteration context is not a list, or there were too many iterations: an error is reported, and
     *         the iteration's value is null; true otherwise, also when {@code visit} stopped it
     */
    static boolean over(List<String> variables, List<Node> domains, Env env, Predicate<Env> visit) {
        int count = variables.size();
        var lists = new List<?>[count];
        var positions = new int[count];
        var scopes = new Env[count + 1];
        scopes[0] = env;
        int level = 0;
        lists[0] = list(domains.get(0), env);
        if (lists[0] == NOT_A_LIST) {
            return false;
        }
        while (level >= 0) {
            if (positions[level] >= lists[level].size()) {
                level--;
                continue;
            }
            if (!env.step()) {
                return false;
            }
            scopes[level + 1] = scopes[level].with(Collections.singletonMap(variables.get(level), lists[level].get(positions[level]++)));
            if (level == count - 1) {
                if (!visit.test(scopes[count])) {
                    return true;
                }
            } else {
                level++;
                positions[level] = 0;
                lists[level] = list(domains.get(level), scopes[level]);
                if (lists[level] == NOT_A_LIST) {
                    return false;
                }
            }
        }
        return true;
    }

    /** What {@link #list} gives for an iteration context that is not a list. */
    private static final List<Object> NOT_A_LIST = Collections.unmodifiableList(new ArrayList<>());

    /** The list an iteration context gives; {@link #NOT_A_LIST}, with an error, if it is not one. */
    private static List<?> list(Node domain, Env env) {
        var value = domain.evaluate(env);
        if (value instanceof List<?> list) {
            return list;
        }
        if (value != null) {
            env.error(() -> "an iteration context is %s, not a list".formatted(FeelValues.text(value)));
        }
        return NOT_A_LIST;
    }

    /** The types of the variables, as each takes an element of its list. */
    static Map<String, FeelType> types(List<String> variables, List<Node> domains, TypeEnv env) {
        var bound = new LinkedHashMap<String, FeelType>();
        var scope = env.with(bound);
        for (int i = 0; i < variables.size(); i++) {
            var type = domains.get(i).type(scope);
            bound.put(variables.get(i), type instanceof FeelType.ListOf(var element) ? element : FeelType.ANY);
        }
        return bound;
    }

    /**
     * The list {@code from..to} stands for in an iteration context: whole numbers or dates, ascending or descending,
     * computed as they are read, at a step an element; null, with an error, for any other endpoints or one of more than
     * {@link Env#MAX_STEPS} elements.
     */
    static Object range(Object from, Object to, Env env) {
        var start = from instanceof Number number ? FeelNumbers.decimal(number) : null;
        var end = to instanceof Number number ? FeelNumbers.decimal(number) : null;
        if (start != null && end != null && isWhole(start) && isWhole(end)) {
            return numbers(start.toBigIntegerExact(), end.toBigIntegerExact(), env);
        }
        if (from instanceof LocalDate a && to instanceof LocalDate b) {
            long days = ChronoUnit.DAYS.between(a, b);
            if (Math.abs(days) >= Env.MAX_STEPS) {
                return tooMany(env);
            }
            int size = (int) Math.abs(days) + 1;
            return env.step(size) ? new Dates(a, days < 0 ? -1 : 1, size) : null;
        }
        return env.error(() -> "%s..%s: an iteration context's range is of whole numbers or dates".formatted(FeelValues.text(from),
                FeelValues.text(to)));
    }

    private static Object numbers(BigInteger from, BigInteger to, Env env) {
        var span = to.subtract(from).abs();
        if (span.compareTo(BigInteger.valueOf(Env.MAX_STEPS)) >= 0) {
            return tooMany(env);
        }
        int size = span.intValueExact() + 1;
        return env.step(size) ? new Numbers(new BigDecimal(from), from.compareTo(to) <= 0 ? 1 : -1, size) : null;
    }

    private static Object tooMany(Env env) {
        return env.error("an iteration context's range has more than " + Env.MAX_STEPS + " elements");
    }

    private static boolean isWhole(BigDecimal number) {
        return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
    }

    /** The whole numbers from {@code first}, a {@code step} of 1 or -1 apart: an immutable list that holds none of them. */
    private static final class Numbers extends AbstractList<Object> implements RandomAccess {
        private final BigDecimal first;
        private final int step;
        private final int size;

        Numbers(BigDecimal first, int step, int size) {
            this.first = first;
            this.step = step;
            this.size = size;
        }

        @Override
        public Object get(int index) {
            return first.add(BigDecimal.valueOf((long) step * java.util.Objects.checkIndex(index, size)));
        }

        @Override
        public int size() {
            return size;
        }
    }

    /** The dates from {@code first}, a {@code step} of a day or minus a day apart: an immutable list that holds none of them. */
    private static final class Dates extends AbstractList<Object> implements RandomAccess {
        private final LocalDate first;
        private final int step;
        private final int size;

        Dates(LocalDate first, int step, int size) {
            this.first = first;
            this.step = step;
            this.size = size;
        }

        @Override
        public Object get(int index) {
            return first.plusDays((long) step * java.util.Objects.checkIndex(index, size));
        }

        @Override
        public int size() {
            return size;
        }
    }
}
