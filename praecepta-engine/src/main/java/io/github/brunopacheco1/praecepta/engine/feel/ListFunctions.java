/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.invalid;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.items;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.list;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.result;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.steps;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.fixed;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.optional;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.variadic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's list functions (DMN 1.5, 10.3.4.4) and {@code sort} (10.3.4.9). Positions count from 1, the first item, or
 * from -1, the last; items are found and told apart by FEEL's equality ({@link FeelValues#equality}), and ordered as
 * FEEL orders values ({@link FeelValues#compare}). A function of a list or of its values ({@code min(list)},
 * {@code min(c1, c2, ...)}) takes either. Each item looked at is a step of the evaluation.
 */
final class ListFunctions {

    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);
    private static final FeelType BOOLEAN = FeelType.of(ColumnType.BOOLEAN);
    private static final FeelType LIST = new FeelType.ListOf(FeelType.ANY);
    private static final String LIST_PARAMETER = "list";
    private static final String POSITION = "position";
    private static final String NEW_ITEM = "newItem";

    private ListFunctions() {
    }

    static void register(Consumer<BuiltInFunction> add) {
        add.accept(BuiltInFunction.of("list contains", BOOLEAN, fixed(2), (arguments, env) -> {
            var list = list(arguments.get(0), LIST_PARAMETER);
            return indexes(list, arguments.get(1), env, true).size() > 0;
        }, LIST_PARAMETER, "element"));
        add.accept(BuiltInFunction.of("count", NUMBER, fixed(1),
                (arguments, env) -> BigDecimal.valueOf(list(arguments.getFirst(), LIST_PARAMETER).size()), LIST_PARAMETER));
        add.accept(BuiltInFunction.of("min", FeelType.ANY, variadic(1), (arguments, env) -> extreme(arguments, env, -1), LIST_PARAMETER));
        add.accept(BuiltInFunction.of("max", FeelType.ANY, variadic(1), (arguments, env) -> extreme(arguments, env, 1), LIST_PARAMETER));
        add.accept(BuiltInFunction.of("sum", NUMBER, variadic(1), (arguments, env) -> {
            var numbers = numbers(arguments, env);
            return numbers.isEmpty() ? null : sum(numbers);
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("mean", NUMBER, variadic(1), (arguments, env) -> {
            var numbers = numbers(arguments, env);
            if (numbers.isEmpty()) {
                throw invalid("takes a list of one number or more, not an empty one");
            }
            return defined(FeelNumbers.divide(sum(numbers), BigDecimal.valueOf(numbers.size())));
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("all", BOOLEAN, variadic(1), (arguments, env) -> logic(arguments, env, false), LIST_PARAMETER));
        add.accept(BuiltInFunction.of("any", BOOLEAN, variadic(1), (arguments, env) -> logic(arguments, env, true), LIST_PARAMETER));
        add.accept(BuiltInFunction.of("sublist", LIST, optional(2, 1), ListFunctions::sublist, LIST_PARAMETER, "start position", "length"));
        add.accept(BuiltInFunction.of("append", LIST, variadic(1), (arguments, env) -> {
            var list = new ArrayList<Object>(list(arguments.getFirst(), LIST_PARAMETER));
            list.addAll(arguments.subList(1, arguments.size()));
            steps(env, list.size());
            return result(list);
        }, LIST_PARAMETER, "item"));
        add.accept(BuiltInFunction.of("concatenate", LIST, variadic(0), (arguments, env) -> {
            var all = new ArrayList<Object>();
            for (var argument : arguments) {
                var list = list(argument, LIST_PARAMETER);
                steps(env, list.size());
                all.addAll(list);
            }
            return result(all);
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("insert before", LIST, fixed(3), (arguments, env) -> {
            var list = new ArrayList<Object>(list(arguments.get(0), LIST_PARAMETER));
            steps(env, list.size());
            list.add(position(arguments.get(1), list.size(), POSITION), arguments.get(2));
            return result(list);
        }, LIST_PARAMETER, POSITION, NEW_ITEM));
        add.accept(BuiltInFunction.of("remove", LIST, fixed(2), (arguments, env) -> {
            var list = new ArrayList<Object>(list(arguments.get(0), LIST_PARAMETER));
            steps(env, list.size());
            list.remove(position(arguments.get(1), list.size(), POSITION));
            return result(list);
        }, LIST_PARAMETER, POSITION));
        add.accept(BuiltInFunction.of("reverse", LIST, fixed(1), (arguments, env) -> {
            var list = new ArrayList<Object>(list(arguments.getFirst(), LIST_PARAMETER));
            steps(env, list.size());
            Collections.reverse(list);
            return result(list);
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("index of", FeelType.listOf(ColumnType.NUMBER), fixed(2), (arguments, env) -> {
            var positions = new ArrayList<Object>();
            for (int index : indexes(list(arguments.get(0), LIST_PARAMETER), arguments.get(1), env, false)) {
                positions.add(BigDecimal.valueOf(index + 1L));
            }
            return result(positions);
        }, LIST_PARAMETER, "match"));
        add.accept(BuiltInFunction.of("union", LIST, variadic(0), (arguments, env) -> {
            var all = new ArrayList<Object>();
            for (var argument : arguments) {
                all.addAll(list(argument, LIST_PARAMETER));
            }
            return distinct(all, env);
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("distinct values", LIST, fixed(1),
                (arguments, env) -> distinct(list(arguments.getFirst(), LIST_PARAMETER), env), LIST_PARAMETER));
        add.accept(BuiltInFunction.of("flatten", LIST, fixed(1), (arguments, env) -> {
            var flat = new ArrayList<Object>();
            flatten(list(arguments.getFirst(), LIST_PARAMETER), flat, env, 0);
            return result(flat);
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("product", NUMBER, variadic(1), (arguments, env) -> {
            var numbers = numbers(arguments, env);
            if (numbers.isEmpty()) {
                throw invalid("takes a list of one number or more, not an empty one");
            }
            var product = BigDecimal.ONE;
            for (var number : numbers) {
                product = defined(FeelNumbers.multiply(product, number));
            }
            return product;
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("median", NUMBER, variadic(1), (arguments, env) -> {
            var numbers = sorted(numbers(arguments, env), env);
            if (numbers.isEmpty()) {
                return null;
            }
            int middle = numbers.size() / 2;
            if (numbers.size() % 2 == 1) {
                return numbers.get(middle);
            }
            return defined(FeelNumbers.divide(FeelNumbers.add((BigDecimal) numbers.get(middle - 1), (BigDecimal) numbers.get(middle)),
                    BigDecimal.valueOf(2)));
        }, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("stddev", NUMBER, variadic(1), ListFunctions::stddev, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("mode", LIST, variadic(1), ListFunctions::mode, LIST_PARAMETER));
        add.accept(BuiltInFunction.of("list replace", LIST, fixed(3), ListFunctions::listReplace,
                List.of(List.of(LIST_PARAMETER, POSITION, NEW_ITEM), List.of(LIST_PARAMETER, "match", NEW_ITEM))));
        add.accept(BuiltInFunction.of("sort", LIST, optional(1, 1), ListFunctions::sort, LIST_PARAMETER, "precedes"));
    }

    /** The positions, from 0, of the items of a list equal to a value; only the first, if {@code first}. */
    private static List<Integer> indexes(List<?> list, Object value, Env env, boolean first) {
        var indexes = new ArrayList<Integer>();
        for (int i = 0; i < list.size(); i++) {
            steps(env, 1);
            if (Boolean.TRUE.equals(FeelValues.equality(list.get(i), value, env))) {
                indexes.add(i);
                if (first) {
                    break;
                }
            }
        }
        return indexes;
    }

    /** The least ({@code sign} -1) or greatest (1) of the values; null if there are none. */
    private static Object extreme(List<Object> arguments, Env env, int sign) {
        var values = items(arguments, LIST_PARAMETER);
        steps(env, values.size());
        Object best = null;
        for (var value : values) {
            if (best == null) {
                best = comparable(value);
                continue;
            }
            var order = FeelValues.compare(value, best);
            if (order == null) {
                throw invalid("takes values that compare with each other, not %s and %s".formatted(FeelValues.text(best),
                        FeelValues.text(value)));
            }
            if (order * sign > 0) {
                best = value;
            }
        }
        return best;
    }

    private static Object comparable(Object value) {
        if (FeelValues.compare(value, value) == null) {
            throw invalid("takes values that compare with each other, not " + FeelValues.text(value));
        }
        return value;
    }

    /** The numbers of a list, or of the arguments. */
    private static List<BigDecimal> numbers(List<Object> arguments, Env env) {
        var values = items(arguments, LIST_PARAMETER);
        steps(env, values.size());
        var numbers = new ArrayList<BigDecimal>(values.size());
        for (var value : values) {
            if (!(value instanceof BigDecimal number)) {
                throw invalid("takes numbers, not " + FeelValues.text(value));
            }
            numbers.add(number);
        }
        return numbers;
    }

    private static BigDecimal sum(List<BigDecimal> numbers) {
        var sum = BigDecimal.ZERO;
        for (var number : numbers) {
            sum = defined(FeelNumbers.add(sum, number));
        }
        return sum;
    }

    private static BigDecimal defined(BigDecimal number) {
        if (number == null) {
            throw invalid("gives a number beyond those FEEL holds");
        }
        return number;
    }

    /**
     * {@code all} ({@code any} false) or {@code any} (true): {@code any} true if an item is true, false if all are
     * false, or there are none; {@code all} the other way round; else null, with an error, an item being neither.
     */
    private static Object logic(List<Object> arguments, Env env, boolean any) {
        var values = items(arguments, LIST_PARAMETER);
        steps(env, values.size());
        boolean unknown = false;
        for (var value : values) {
            if (value instanceof Boolean bool) {
                if (bool == any) {
                    return any;
                }
            } else {
                unknown = true;
            }
        }
        if (unknown) {
            throw invalid("takes a list of booleans, not one holding another value");
        }
        return !any;
    }

    private static Object sublist(List<Object> arguments, Env env) {
        var list = list(arguments.get(0), LIST_PARAMETER);
        int start = Arguments.whole(arguments.get(1), "start position");
        int from = start > 0 ? start - 1 : list.size() + start;
        if (start == 0 || from < 0 || from >= list.size()) {
            throw invalid("takes a start position from 1 to %d, or -1 to -%d, not %d".formatted(list.size(), list.size(), start));
        }
        int to = list.size();
        if (arguments.size() > 2 && arguments.get(2) != null) {
            int length = Arguments.whole(arguments.get(2), "length");
            if (length < 0 || from + (long) length > list.size()) {
                throw invalid("takes a length from 0 to %d, not %d".formatted(list.size() - from, length));
            }
            to = from + length;
        }
        steps(env, to - from);
        return result(list.subList(from, to));
    }

    /** A position in a list of {@code size} items, 1 to size or -1 to -size, as an index from 0. */
    private static int position(Object value, int size, String parameter) {
        int position = Arguments.whole(value, parameter);
        int index = position > 0 ? position - 1 : size + position;
        if (position == 0 || index < 0 || index >= size) {
            throw invalid("takes a %s from 1 to %d, or -1 to -%d, not %d".formatted(parameter, size, size, position));
        }
        return index;
    }

    /** The items of a list without those equal to one before them. */
    private static List<Object> distinct(List<?> list, Env env) {
        var distinct = new ArrayList<Object>();
        for (var item : list) {
            if (indexes(distinct, item, env, true).isEmpty()) {
                distinct.add(item);
            }
        }
        return result(distinct);
    }

    private static void flatten(List<?> list, List<Object> flat, Env env, int depth) {
        if (depth > Env.MAX_NESTING) {
            throw invalid("takes lists nested at most %d deep".formatted(Env.MAX_NESTING));
        }
        for (var item : list) {
            steps(env, 1);
            if (item instanceof List<?> nested) {
                flatten(nested, flat, env, depth + 1);
            } else {
                flat.add(item);
            }
        }
    }

    /** The sample standard deviation of the values; null for fewer than two. */
    private static Object stddev(List<Object> arguments, Env env) {
        var numbers = numbers(arguments, env);
        if (numbers.size() < 2) {
            throw invalid("takes a list of two numbers or more, not one of " + numbers.size());
        }
        var count = BigDecimal.valueOf(numbers.size());
        var mean = defined(FeelNumbers.divide(sum(numbers), count));
        var squares = BigDecimal.ZERO;
        for (var number : numbers) {
            var deviation = defined(FeelNumbers.subtract(number, mean));
            squares = defined(FeelNumbers.add(squares, defined(FeelNumbers.multiply(deviation, deviation))));
        }
        return defined(FeelNumbers.sqrt(defined(FeelNumbers.divide(squares, count.subtract(BigDecimal.ONE)))));
    }

    /** The values that occur most often, in ascending order; none for no values. */
    private static Object mode(List<Object> arguments, Env env) {
        var values = sorted(items(arguments, LIST_PARAMETER), env);
        var modes = new ArrayList<Object>();
        int most = 0;
        for (int i = 0; i < values.size();) {
            int j = i + 1;
            while (j < values.size() && Boolean.TRUE.equals(FeelValues.equality(values.get(i), values.get(j), env))) {
                j++;
            }
            if (j - i > most) {
                modes.clear();
                most = j - i;
            }
            if (j - i == most) {
                modes.add(values.get(i));
            }
            i = j;
        }
        return result(modes);
    }

    /**
     * {@code list replace(list, position, newItem)}, the item at a position replaced, or
     * {@code list replace(list, match, newItem)}, each item for which {@code match(item, newItem)} is true replaced.
     */
    private static Object listReplace(List<Object> arguments, Env env) {
        var list = new ArrayList<Object>(list(arguments.get(0), LIST_PARAMETER));
        steps(env, list.size());
        var newItem = arguments.get(2);
        if (Arguments.single(arguments.get(1)) instanceof FeelFunction match) {
            for (int i = 0; i < list.size(); i++) {
                var matches = Node.Invocation.call(match, Arrays.asList(list.get(i), newItem), env);
                if (!(matches instanceof Boolean)) {
                    throw invalid("takes a match function that gives a boolean, not " + FeelValues.text(matches));
                }
                if ((Boolean) matches) {
                    list.set(i, newItem);
                }
            }
            return result(list);
        }
        list.set(position(arguments.get(1), list.size(), POSITION), newItem);
        return result(list);
    }

    /**
     * {@code sort(list, precedes?)}: the items in the order {@code precedes(x, y)}, a function giving true when
     * {@code x} comes before {@code y}, puts them, items it puts in no order keeping theirs; without it, in ascending
     * order.
     */
    private static Object sort(List<Object> arguments, Env env) {
        var list = list(arguments.get(0), LIST_PARAMETER);
        if (arguments.size() < 2 || arguments.get(1) == null) {
            return result(sorted(list, env));
        }
        var precedes = Arguments.function(arguments.get(1), "precedes");
        return result(mergeSort(new ArrayList<>(list), (x, y) -> {
            var before = Node.Invocation.call(precedes, Arrays.asList(x, y), env);
            if (!(before instanceof Boolean bool)) {
                throw env.stopped() ? new BuiltInFunction.Invalid(null)
                        : invalid("takes a precedes function that gives a boolean, not " + FeelValues.text(before));
            }
            return bool;
        }, env));
    }

    /** The values in ascending order, as FEEL orders them. */
    private static List<Object> sorted(List<?> values, Env env) {
        for (var value : values) {
            comparable(value);
        }
        return mergeSort(new ArrayList<>(values), (x, y) -> {
            var order = FeelValues.compare(x, y);
            if (order == null) {
                throw invalid("takes values that compare with each other, not %s and %s".formatted(FeelValues.text(x), FeelValues.text(y)));
            }
            return order < 0;
        }, env);
    }

    /** Whether one value comes before another. */
    @FunctionalInterface
    private interface Precedes {
        boolean test(Object x, Object y);
    }

    /**
     * A stable merge sort, by a test that need not be a total order: an item goes before one on its left only when the
     * test puts it before it. Each comparison is a step of the evaluation.
     */
    private static List<Object> mergeSort(List<Object> items, Precedes precedes, Env env) {
        if (items.size() < 2) {
            return items;
        }
        int middle = items.size() / 2;
        var left = mergeSort(new ArrayList<>(items.subList(0, middle)), precedes, env);
        var right = mergeSort(new ArrayList<>(items.subList(middle, items.size())), precedes, env);
        var merged = new ArrayList<Object>(items.size());
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            steps(env, 1);
            merged.add(precedes.test(right.get(j), left.get(i)) ? right.get(j++) : left.get(i++));
        }
        merged.addAll(left.subList(i, left.size()));
        merged.addAll(right.subList(j, right.size()));
        return merged;
    }
}
