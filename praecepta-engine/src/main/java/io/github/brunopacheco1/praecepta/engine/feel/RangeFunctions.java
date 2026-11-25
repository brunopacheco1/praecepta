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

    /** What a function gives for each of the kinds of arguments it takes; null for those it does not take. */
    private interface Relation {
        default Boolean points(Object a, Object b) {
            return null;
        }

        default Boolean pointRange(Object point, Interval range) {
            return null;
        }

        default Boolean rangePoint(Interval range, Object point) {
            return null;
        }

        Boolean ranges(Interval a, Interval b);
    }

    private RangeFunctions() {
    }

    static void register(Consumer<BuiltInFunction> add) {
        var before = new Relation() {
            @Override
            public Boolean points(Object a, Object b) {
                return lt(a, b);
            }

            @Override
            public Boolean pointRange(Object point, Interval range) {
                return lt(point, range.start()) || eq(point, range.start()) && !range.startIncluded();
            }

            @Override
            public Boolean rangePoint(Interval range, Object point) {
                return lt(range.end(), point) || eq(range.end(), point) && !range.endIncluded();
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return lt(a.end(), b.start()) || (!a.endIncluded() || !b.startIncluded()) && eq(a.end(), b.start());
            }
        };
        add(add, "before", before, List.of(POINTS, POINT_RANGE, RANGE_POINT, RANGES));
        add(add, "after", new Relation() {
            @Override
            public Boolean points(Object a, Object b) {
                return before.points(b, a);
            }

            @Override
            public Boolean pointRange(Object point, Interval range) {
                return before.rangePoint(range, point);
            }

            @Override
            public Boolean rangePoint(Interval range, Object point) {
                return before.pointRange(point, range);
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return before.ranges(b, a);
            }
        }, List.of(POINTS, POINT_RANGE, RANGE_POINT, RANGES));
        Relation meets = (a, b) -> a.endIncluded() && b.startIncluded() && eq(a.end(), b.start());
        add(add, "meets", meets, List.of(RANGES));
        add(add, "met by", (a, b) -> meets.ranges(b, a), List.of(RANGES));
        add(add, "overlaps", (a, b) -> (gt(a.end(), b.start()) || eq(a.end(), b.start()) && a.endIncluded() && b.startIncluded())
                && (lt(a.start(), b.end()) || eq(a.start(), b.end()) && a.startIncluded() && b.endIncluded()), List.of(RANGES));
        Relation overlapsBefore = (a, b) -> (lt(a.start(), b.start()) || eq(a.start(), b.start()) && a.startIncluded() && !b.startIncluded())
                && (gt(a.end(), b.start()) || eq(a.end(), b.start()) && a.endIncluded() && b.startIncluded())
                && (lt(a.end(), b.end()) || eq(a.end(), b.end()) && (!a.endIncluded() || b.endIncluded()));
        add(add, "overlaps before", overlapsBefore, List.of(RANGES));
        add(add, "overlaps after", (a, b) -> overlapsBefore.ranges(b, a), List.of(RANGES));
        var finishes = new Relation() {
            @Override
            public Boolean pointRange(Object point, Interval range) {
                return range.endIncluded() && eq(range.end(), point);
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return a.endIncluded() == b.endIncluded() && eq(a.end(), b.end())
                        && (gt(a.start(), b.start()) || eq(a.start(), b.start()) && (!a.startIncluded() || b.startIncluded()));
            }
        };
        add(add, "finishes", finishes, List.of(POINT_RANGE, RANGES));
        add(add, "finished by", new Relation() {
            @Override
            public Boolean rangePoint(Interval range, Object point) {
                return finishes.pointRange(point, range);
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return finishes.ranges(b, a);
            }
        }, List.of(RANGE_POINT, RANGES));
        var includes = new Relation() {
            @Override
            public Boolean rangePoint(Interval range, Object point) {
                return lt(range.start(), point) && gt(range.end(), point)
                        || eq(range.start(), point) && range.startIncluded() || eq(range.end(), point) && range.endIncluded();
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return (lt(a.start(), b.start()) || eq(a.start(), b.start()) && (a.startIncluded() || !b.startIncluded()))
                        && (gt(a.end(), b.end()) || eq(a.end(), b.end()) && (a.endIncluded() || !b.endIncluded()));
            }
        };
        add(add, "includes", includes, List.of(RANGE_POINT, RANGES));
        add(add, "during", new Relation() {
            @Override
            public Boolean pointRange(Object point, Interval range) {
                return includes.rangePoint(range, point);
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return includes.ranges(b, a);
            }
        }, List.of(POINT_RANGE, RANGES));
        var starts = new Relation() {
            @Override
            public Boolean pointRange(Object point, Interval range) {
                return eq(range.start(), point) && range.startIncluded();
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return eq(a.start(), b.start()) && a.startIncluded() == b.startIncluded()
                        && (lt(a.end(), b.end()) || eq(a.end(), b.end()) && (!a.endIncluded() || b.endIncluded()));
            }
        };
        add(add, "starts", starts, List.of(POINT_RANGE, RANGES));
        add(add, "started by", new Relation() {
            @Override
            public Boolean rangePoint(Interval range, Object point) {
                return starts.pointRange(point, range);
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return starts.ranges(b, a);
            }
        }, List.of(RANGE_POINT, RANGES));
        add(add, "coincides", new Relation() {
            @Override
            public Boolean points(Object a, Object b) {
                return eq(a, b);
            }

            @Override
            public Boolean ranges(Interval a, Interval b) {
                return eq(a.start(), b.start()) && a.startIncluded() == b.startIncluded()
                        && eq(a.end(), b.end()) && a.endIncluded() == b.endIncluded();
            }
        }, List.of(POINTS, RANGES));
    }

    private static void add(Consumer<BuiltInFunction> add, String name, Relation relation, List<List<String>> signatures) {
        add.accept(BuiltInFunction.of(name, BOOLEAN, fixed(2), (arguments, env) -> {
            var a = Arguments.single(arguments.get(0));
            var b = Arguments.single(arguments.get(1));
            if (a == null || b == null) {
                throw invalid("takes a point or a range, not null");
            }
            var result = switch (a) {
                case FeelRange first when b instanceof FeelRange second -> relation.ranges(interval(first), interval(second));
                case FeelRange first -> relation.rangePoint(interval(first), b);
                default -> b instanceof FeelRange second ? relation.pointRange(a, interval(second)) : relation.points(a, b);
            };
            if (result == null) {
                throw invalid("takes %s, not %s and %s".formatted(takes(signatures), kind(a), kind(b)));
            }
            return result;
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
