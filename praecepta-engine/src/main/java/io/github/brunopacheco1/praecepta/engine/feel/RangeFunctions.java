/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.invalid;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.fixed;

import java.util.List;
import java.util.function.Consumer;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's range functions (DMN 1.5, 10.3.4.7): how a point or a range is placed with regard to another, as Allen's
 * interval algebra has it, by the formulas of DMN 1.5's Table 78 and the tables after it. Each takes two points, a
 * point and a range, a range and a point, or two ranges, as it defines; a range's missing endpoint, as {@code < 10}
 * has, is unbounded, and excluded. Endpoints and points that FEEL does not order with each other give null, with an
 * error, as do {@code = e} and {@code != e}, which are not intervals.
 */
final class RangeFunctions {

    private static final FeelType BOOLEAN = FeelType.of(ColumnType.BOOLEAN);
    private static final List<String> POINTS = List.of("point1", "point2");
    private static final List<String> POINT_RANGE = List.of("point", "range");
    private static final List<String> RANGE_POINT = List.of("range", "point");
    private static final List<String> RANGES = List.of("range1", "range2");

    /** An unbounded endpoint: below every value, at a range's start; above every value, at its end. */
    private enum Unbounded {
        BELOW,
        ABOVE
    }

    /** A range as these formulas read it: its endpoints, {@link Unbounded} where it has none, and whether each is in it. */
    private record Interval(Object start, boolean startIncluded, Object end, boolean endIncluded) {
    }

    /** Thrown for a kind of arguments a function does not take, which {@link #add} reports with the kinds it takes. */
    private static final class NotTaken extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotTaken() {
            super(null, null, false, false);
        }
    }

    /** What a function gives for each of the kinds of arguments it takes; {@link NotTaken} for those it does not take. */
    private interface Relation {
        default boolean points(Object a, Object b) {
            throw new NotTaken();
        }

        default boolean pointRange(Object point, Interval range) {
            throw new NotTaken();
        }

        default boolean rangePoint(Interval range, Object point) {
            throw new NotTaken();
        }

        boolean ranges(Interval a, Interval b);
    }

    /** The relation of the arguments the other way round: {@code after} of {@code before}. */
    private record Converse(Relation of) implements Relation {
        @Override
        public boolean points(Object x, Object y) {
            return of.points(y, x);
        }

        @Override
        public boolean pointRange(Object point, Interval range) {
            return of.rangePoint(range, point);
        }

        @Override
        public boolean rangePoint(Interval range, Object point) {
            return of.pointRange(point, range);
        }

        @Override
        public boolean ranges(Interval x, Interval y) {
            return of.ranges(y, x);
        }
    }

    private static final Relation BEFORE = new Relation() {
        @Override
        public boolean points(Object a, Object b) {
            return lt(a, b);
        }

        @Override
        public boolean pointRange(Object point, Interval range) {
            return lt(point, range.start()) || eq(point, range.start()) && !range.startIncluded();
        }

        @Override
        public boolean rangePoint(Interval range, Object point) {
            return lt(range.end(), point) || eq(range.end(), point) && !range.endIncluded();
        }

        @Override
        public boolean ranges(Interval a, Interval b) {
            return lt(a.end(), b.start()) || (!a.endIncluded() || !b.startIncluded()) && eq(a.end(), b.start());
        }
    };

    private static final Relation MEETS = (a, b) -> a.endIncluded() && b.startIncluded() && eq(a.end(), b.start());

    private static final Relation OVERLAPS = (a, b) -> (gt(a.end(), b.start()) || eq(a.end(), b.start()) && a.endIncluded()
            && b.startIncluded()) && (lt(a.start(), b.end()) || eq(a.start(), b.end()) && a.startIncluded() && b.endIncluded());

    private static final Relation OVERLAPS_BEFORE = (a, b) -> (lt(a.start(), b.start()) || eq(a.start(), b.start())
            && a.startIncluded() && !b.startIncluded())
            && (gt(a.end(), b.start()) || eq(a.end(), b.start()) && a.endIncluded() && b.startIncluded())
            && (lt(a.end(), b.end()) || eq(a.end(), b.end()) && (!a.endIncluded() || b.endIncluded()));

    private static final Relation FINISHES = new Relation() {
        @Override
        public boolean pointRange(Object point, Interval range) {
            return range.endIncluded() && eq(range.end(), point);
        }

        @Override
        public boolean ranges(Interval a, Interval b) {
            return a.endIncluded() == b.endIncluded() && eq(a.end(), b.end())
                    && (gt(a.start(), b.start()) || eq(a.start(), b.start()) && (!a.startIncluded() || b.startIncluded()));
        }
    };

    private static final Relation INCLUDES = new Relation() {
        @Override
        public boolean rangePoint(Interval range, Object point) {
            return lt(range.start(), point) && gt(range.end(), point)
                    || eq(range.start(), point) && range.startIncluded() || eq(range.end(), point) && range.endIncluded();
        }

        @Override
        public boolean ranges(Interval a, Interval b) {
            return (lt(a.start(), b.start()) || eq(a.start(), b.start()) && (a.startIncluded() || !b.startIncluded()))
                    && (gt(a.end(), b.end()) || eq(a.end(), b.end()) && (a.endIncluded() || !b.endIncluded()));
        }
    };

    private static final Relation STARTS = new Relation() {
        @Override
        public boolean pointRange(Object point, Interval range) {
            return eq(range.start(), point) && range.startIncluded();
        }

        @Override
        public boolean ranges(Interval a, Interval b) {
            return eq(a.start(), b.start()) && a.startIncluded() == b.startIncluded()
                    && (lt(a.end(), b.end()) || eq(a.end(), b.end()) && (!a.endIncluded() || b.endIncluded()));
        }
    };

    private static final Relation COINCIDES = new Relation() {
        @Override
        public boolean points(Object a, Object b) {
            return eq(a, b);
        }

        @Override
        public boolean ranges(Interval a, Interval b) {
            return eq(a.start(), b.start()) && a.startIncluded() == b.startIncluded()
                    && eq(a.end(), b.end()) && a.endIncluded() == b.endIncluded();
        }
    };

    private RangeFunctions() {
    }

    static void register(Consumer<BuiltInFunction> add) {
        var all = List.of(POINTS, POINT_RANGE, RANGE_POINT, RANGES);
        add(add, "before", BEFORE, all);
        add(add, "after", new Converse(BEFORE), all);
        add(add, "meets", MEETS, List.of(RANGES));
        add(add, "met by", new Converse(MEETS), List.of(RANGES));
        add(add, "overlaps", OVERLAPS, List.of(RANGES));
        add(add, "overlaps before", OVERLAPS_BEFORE, List.of(RANGES));
        add(add, "overlaps after", new Converse(OVERLAPS_BEFORE), List.of(RANGES));
        add(add, "finishes", FINISHES, List.of(POINT_RANGE, RANGES));
        add(add, "finished by", new Converse(FINISHES), List.of(RANGE_POINT, RANGES));
        add(add, "includes", INCLUDES, List.of(RANGE_POINT, RANGES));
        add(add, "during", new Converse(INCLUDES), List.of(POINT_RANGE, RANGES));
        add(add, "starts", STARTS, List.of(POINT_RANGE, RANGES));
        add(add, "started by", new Converse(STARTS), List.of(RANGE_POINT, RANGES));
        add(add, "coincides", COINCIDES, List.of(POINTS, RANGES));
    }

    private static void add(Consumer<BuiltInFunction> add, String name, Relation relation, List<List<String>> signatures) {
        add.accept(BuiltInFunction.of(name, BOOLEAN, fixed(2), (arguments, env) -> {
            var a = Arguments.single(arguments.get(0));
            var b = Arguments.single(arguments.get(1));
            if (a == null || b == null) {
                throw invalid("takes a point or a range, not null");
            }
            try {
                return switch (a) {
                    case FeelRange first when b instanceof FeelRange second -> relation.ranges(interval(first), interval(second));
                    case FeelRange first -> relation.rangePoint(interval(first), b);
                    default -> b instanceof FeelRange second ? relation.pointRange(a, interval(second)) : relation.points(a, b);
                };
            } catch (NotTaken e) {
                throw invalid("takes %s, not %s and %s".formatted(takes(signatures), kind(a), kind(b)));
            }
        }, signatures));
    }

    private static String takes(List<List<String>> signatures) {
        return String.join(", or ", signatures.stream().map(signature -> String.join(" and ", signature.stream()
                .map(parameter -> "a " + parameter.replaceAll("\\d", "")).toList())).toList());
    }

    private static String kind(Object value) {
        return value instanceof FeelRange ? "a range" : "a point";
    }

    private static Interval interval(FeelRange range) {
        if (range.comparison() == FeelRange.Comparison.EQUAL || range.comparison() == FeelRange.Comparison.NOT_EQUAL) {
            throw invalid("takes ranges that are intervals, not " + range);
        }
        return new Interval(range.start() == null ? Unbounded.BELOW : range.start(), range.start() != null && range.startIncluded(),
                range.end() == null ? Unbounded.ABOVE : range.end(), range.end() != null && range.endIncluded());
    }

    private static boolean lt(Object a, Object b) {
        return compare(a, b) < 0;
    }

    private static boolean gt(Object a, Object b) {
        return compare(a, b) > 0;
    }

    private static boolean eq(Object a, Object b) {
        return compare(a, b) == 0;
    }

    /** How two endpoints or points compare, an unbounded one below or above every value. */
    private static int compare(Object a, Object b) {
        if (a instanceof Unbounded || b instanceof Unbounded) {
            return Integer.compare(rank(a), rank(b));
        }
        var order = FeelValues.compare(a, b);
        if (order == null) {
            throw invalid("takes points and endpoints FEEL orders with each other, not %s and %s"
                    .formatted(FeelValues.text(a), FeelValues.text(b)));
        }
        return order;
    }

    private static int rank(Object value) {
        if (value == Unbounded.BELOW) {
            return -1;
        }
        return value == Unbounded.ABOVE ? 1 : 0;
    }
}
