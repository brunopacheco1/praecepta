/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/**
 * Executable specification of decision-table semantics: a deliberately naive matcher that evaluates
 * every cell of every rule against concrete values, plus strict DMN 1.3 hit-policy rules (§8.2.12).
 * It is the source of truth for the differential tests; performance is irrelevant.
 *
 * <p>Cells are parsed with the engine's {@link CellParser} (checked by its own example tests), but
 * evaluated here, directly on values, with FEEL's three-valued logic: a missing value fails
 * comparisons and intervals (unknown), equals only {@code null}, and so satisfies {@code not("A")}.
 * Blank strings count as missing. Times and date-times with a time zone compare by the instant they
 * denote, and comparing one with a value without a zone is unknown, equality included. The engine
 * instead precomputes value classes and bitmasks.
 *
 * @param rules          rules in table order (DMN "rule order")
 * @param hitPolicy      DMN hit policy
 * @param outputPriority for PRIORITY / OUTPUT_ORDER: per output column, its values from highest to
 *                       lowest priority (DMN output value lists), compared in map iteration order
 * @param types          column types; columns not listed are STRING
 * @param allowedValues  declared input values per column, as unary tests of the column's type
 * @param feelCells      whether cells are strict FEEL, where a name compares with another input
 * @param zonedColumns   the time and date-time columns whose inputs have a time zone; the others have none
 */
public record ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
        Map<String, ColumnType> types, Map<String, String> allowedValues, boolean feelCells, Set<String> zonedColumns) {

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
            Map<String, ColumnType> types, Map<String, String> allowedValues, boolean feelCells) {
        this(rules, hitPolicy, outputPriority, types, allowedValues, feelCells, Set.of());
    }

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy) {
        this(rules, hitPolicy, Map.of());
    }

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority) {
        this(rules, hitPolicy, outputPriority, Map.of(), Map.of());
    }

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
            Map<String, ColumnType> types, Map<String, String> allowedValues) {
        this(rules, hitPolicy, outputPriority, types, allowedValues, false);
    }

    /** Result of evaluating a decision table under strict DMN semantics. */
    public sealed interface Outcome {
        /** Hits in the order mandated by the hit policy (single-hit policies yield at most one). */
        record Hits(List<PraeceptaOutput> outputs) implements Outcome {
        }

        /** The table violates its hit policy for this input (DMN: evaluation error / null result). */
        record Violation(String reason) implements Outcome {
        }

        /** A value is outside its column's declared input values. */
        record InvalidInput(String column) implements Outcome {
        }
    }

    // ---- evaluation ---------------------------------------------------------------------------

    /** All rules whose every cell accepts the input, in table order. */
    public List<Praeceptum> matches(Map<String, ?> input) {
        var matches = new ArrayList<Praeceptum>();
        for (var rule : rules) {
            if (ruleAccepts(rule, input)) {
                matches.add(rule);
            }
        }
        return matches;
    }

    /** The first column whose (non-missing) value is outside its declared input values, or null. */
    public String invalidColumn(Map<String, ?> input) {
        for (var declared : allowedValues.entrySet()) {
            var value = value(input, declared.getKey());
            if (value != null && !accepts(CellParser.parse(declared.getValue(), type(declared.getKey())), value, name -> null)) {
                return declared.getKey();
            }
        }
        return null;
    }

    public Outcome evaluate(Map<String, ?> input) {
        var invalid = invalidColumn(input);
        if (invalid != null) {
            return new Outcome.InvalidInput(invalid);
        }
        var matches = matches(input);
        var outputs = matches.stream().map(rule -> output(rule, input)).toList();
        return switch (hitPolicy) {
            case UNIQUE -> matches.size() > 1
                    ? new Outcome.Violation("UNIQUE: %d rules match".formatted(matches.size()))
                    : new Outcome.Hits(outputs);
            case ANY -> Set.copyOf(outputs).size() > 1
                    ? new Outcome.Violation("ANY: matching rules disagree on outputs")
                    : new Outcome.Hits(outputs.isEmpty() ? List.of() : List.of(outputs.getFirst()));
            case FIRST -> new Outcome.Hits(outputs.isEmpty() ? List.of() : List.of(outputs.getFirst()));
            case PRIORITY -> new Outcome.Hits(outputs.stream().sorted(byPriority()).limit(1).toList());
            case RULE_ORDER, COLLECT -> new Outcome.Hits(outputs);
            case OUTPUT_ORDER -> new Outcome.Hits(outputs.stream().sorted(byPriority()).toList());
        };
    }

    /**
     * DMN COLLECT with an aggregator over a single numeric output column, skipping missing outputs.
     * COUNT counts the matching rules. No match → null (COUNT → 0).
     */
    public BigDecimal aggregate(Map<String, ?> input, String output, Aggregator aggregator) {
        if (hitPolicy != HitPolicy.COLLECT) {
            throw new IllegalStateException("DMN only allows aggregation with COLLECT");
        }
        var matched = matches(input);
        var values = matched.stream()
                .map(rule -> rule.output().values().get(output))
                .filter(Objects::nonNull)
                .map(BigDecimal::new)
                .toList();
        return switch (aggregator) {
            case COUNT -> BigDecimal.valueOf(matched.size());
            case SUM -> values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case MIN -> values.stream().min(Comparator.naturalOrder()).orElse(null);
            case MAX -> values.stream().max(Comparator.naturalOrder()).orElse(null);
        };
    }

    public enum Aggregator { COUNT, SUM, MIN, MAX }

    /**
     * The rule's outputs for this input: an output that names an input takes the text of the input's
     * value (null if missing), as plain outputs are text.
     */
    public PraeceptaOutput output(Praeceptum rule, Map<String, ?> input) {
        if (rule.output().references().isEmpty()) {
            return rule.output();
        }
        var values = new HashMap<>(rule.output().values());
        rule.output().references().forEach((output, column) -> {
            var value = value(input, column);
            values.put(output, value == null ? null : FeelLiterals.text(value));
        });
        return new PraeceptaOutput(values);
    }

    // ---- static analysis, by brute force --------------------------------------------------------

    /** An expected validation finding: the kind name and the rule ids involved, in rule order. */
    public record Finding(String kind, List<Integer> ruleIds) {
    }

    /**
     * Expected overlap and reachability findings. Every input region is visited by evaluating every
     * combination of {@link #representativeValues representative values}; invalid inputs are skipped,
     * since evaluation rejects them before matching.
     */
    public Set<Finding> expectedFindings(List<String> columns) {
        var findings = new LinkedHashSet<Finding>();
        var selected = new HashSet<Praeceptum>();
        for (var input : representativeInputs(columns, true)) {
            if (invalidColumn(input) != null) {
                continue;
            }
            // rules with cells naming inputs match inputs known only when evaluating: they never overlap,
            // and never hide another rule, but they are unreachable if their other cells are covered
            var matches = matches(input).stream().filter(rule -> !isVariable(rule)).toList();
            var seen = rules.stream().filter(rule -> staticallyAccepts(rule, input)).toList();
            switch (hitPolicy) {
                case UNIQUE -> {
                    for (int a = 0; a < matches.size(); a++) {
                        for (int b = a + 1; b < matches.size(); b++) {
                            findings.add(new Finding("OVERLAPPING_RULES", ids(List.of(matches.get(a), matches.get(b)))));
                        }
                    }
                }
                case ANY -> {
                    for (int a = 0; a < matches.size(); a++) {
                        for (int b = a + 1; b < matches.size(); b++) {
                            if (!matches.get(a).output().equals(matches.get(b).output())) {
                                findings.add(new Finding("CONFLICTING_OUTPUTS", ids(List.of(matches.get(a), matches.get(b)))));
                            }
                        }
                    }
                }
                case FIRST -> selectUpToFirstStatic(seen, selected);
                case PRIORITY -> selectUpToFirstStatic(seen.stream().sorted(Comparator.comparing(Praeceptum::output, byPriority())).toList(),
                        selected);
                default -> {
                    // multi-hit policies have no static findings
                }
            }
        }
        if (hitPolicy == HitPolicy.FIRST || hitPolicy == HitPolicy.PRIORITY) {
            for (var rule : rules) {
                if (!selected.contains(rule)) {
                    findings.add(new Finding("UNREACHABLE_RULE", List.of(rule.priority())));
                }
            }
        }
        return findings;
    }

    /** In order, the rules the index sees matching, up to the first that has no cell naming an input. */
    private void selectUpToFirstStatic(List<Praeceptum> seen, Set<Praeceptum> selected) {
        for (var rule : seen) {
            selected.add(rule);
            if (!isVariable(rule)) {
                return;
            }
        }
    }

    /**
     * Gaps are only analysed when every string column the rules constrain either declares its input
     * values or compares (then, like a number column, it has no single class of unmentioned values),
     * and no cell names another input.
     */
    public boolean gapAnalysisApplies(List<String> columns) {
        if (rules.stream().anyMatch(this::isVariable)) {
            return false;
        }
        for (var column : columns) {
            var cells = rules.stream().map(rule -> parse(rule.input().values().get(column), column)).toList();
            boolean constrained = cells.stream().anyMatch(cell -> !(cell instanceof UnaryTests.Any));
            boolean compares = cells.stream().flatMap(cell -> tests(cell).stream())
                    .anyMatch(test -> test instanceof Compare || test instanceof Range);
            if (type(column) == ColumnType.STRING && constrained && !compares && !allowedValues.containsKey(column)) {
                return false;
            }
        }
        return true;
    }

    /** Valid, non-missing representative inputs that no rule matches. */
    public List<Map<String, Object>> uncoveredInputs(List<String> columns) {
        return representativeInputs(columns, false).stream()
                .filter(input -> invalidColumn(input) == null && matches(input).isEmpty())
                .toList();
    }

    /**
     * Values that visit every region the rules (and declared input values) distinguish: every value
     * written, the points between and around them (for dates, the neighbouring days, since there is
     * no date between consecutive days), both booleans, a string no rule mentions, and optionally a
     * missing value.
     */
    public List<Object> representativeValues(String column, boolean withMissing) {
        var type = type(column);
        var cells = new ArrayList<UnaryTests>();
        rules.forEach(rule -> cells.add(parse(rule.input().values().get(column), column)));
        if (allowedValues.containsKey(column)) {
            cells.add(parse(allowedValues.get(column), column));
        }
        var values = new ArrayList<Object>();
        switch (type) {
            case BOOLEAN -> values.addAll(List.of(false, true)); // the whole domain
            case STRING -> values.addAll(stringRepresentatives(cells));
            // years and months durations as the Period a getter returns
            case YEAR_MONTH_DURATION -> orderedRepresentatives(type, cells, false).forEach(v -> values.add(((YearsAndMonths) v).toPeriod()));
            // a column's times take a zone or none, as its getter does
            default -> values.addAll(orderedRepresentatives(type, cells, zonedColumns.contains(column)));
        }
        if (withMissing) {
            values.add(null);
        }
        return values;
    }

    /**
     * Every input that extends {@code partial} with a representative value for each of {@code columns}:
     * the cartesian product of their {@link #representativeValues representative values}.
     */
    public List<Map<String, Object>> representativeInputs(Map<String, Object> partial, List<String> columns, boolean withMissing) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        inputs.add(new HashMap<>(partial));
        for (var column : columns) {
            var next = new ArrayList<Map<String, Object>>();
            for (var shorter : inputs) {
                for (var option : representativeValues(column, withMissing)) {
                    var input = new HashMap<>(shorter);
                    input.put(column, option);
                    next.add(input);
                }
            }
            inputs = next;
        }
        return inputs;
    }

    private List<Map<String, Object>> representativeInputs(List<String> columns, boolean withMissing) {
        return representativeInputs(Map.of(), columns, withMissing);
    }

    /**
     * Every string the cells mention, sorted, and one they do not. For comparisons and intervals, also
     * a string below them all (U+0000, the lowest non-blank string) and each one's successor (the
     * string followed by U+0000), which lies after it and before or on the next one.
     */
    private static List<Object> stringRepresentatives(List<UnaryTests> cells) {
        var written = new TreeSet<String>();
        cells.forEach(cell -> literals(cell).forEach(v -> written.add((String) v)));
        var values = new TreeSet<String>(written);
        var lowest = String.valueOf(Character.MIN_VALUE);
        values.add(lowest);
        written.forEach(value -> values.add(value + lowest));
        var result = new ArrayList<Object>(values);
        result.add("other-value");
        return result;
    }

    /**
     * The values the cells mention, the points around each and between neighbours, in order. Times and
     * date-times only with a time zone if {@code zoned}, else only without one: the values of the other kind
     * cannot be compared with the inputs, so they split nothing.
     */
    private static Collection<Object> orderedRepresentatives(ColumnType type, List<UnaryTests> cells, boolean zoned) {
        var points = new TreeSet<Object>(ReferenceMatcher::compare);
        cells.forEach(cell -> literals(cell).stream().filter(value -> isZoned(value) == zoned).forEach(points::add));
        var sorted = new ArrayList<>(points);
        var around = new TreeSet<Object>(ReferenceMatcher::compare);
        around.addAll(sorted);
        if (sorted.isEmpty()) {
            around.add(switch (type) {
                case NUMBER -> BigDecimal.ZERO;
                case TIME -> zoned ? OffsetTime.of(LocalTime.NOON, ZoneOffset.UTC) : LocalTime.NOON;
                case DATE_TIME -> zoned ? ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC) : LocalDateTime.of(2025, 1, 1, 0, 0);
                case DAY_TIME_DURATION -> Duration.ZERO;
                case YEAR_MONTH_DURATION -> new YearsAndMonths(0);
                default -> LocalDate.of(2025, 1, 1);
            });
        }
        for (int i = 0; i < sorted.size(); i++) {
            var next = i + 1 < sorted.size() ? sorted.get(i + 1) : null;
            addNeighbours(around, sorted.get(i), next);
        }
        return around;
    }

    /** The values just below and above {@code point}, and halfway to {@code next} (null after the last point). */
    private static void addNeighbours(Set<Object> around, Object point, Object next) {
        switch (point) {
            case BigDecimal number -> {
                around.add(number.subtract(BigDecimal.ONE));
                around.add(number.add(BigDecimal.ONE));
                if (next != null) {
                    around.add(number.add((BigDecimal) next).divide(BigDecimal.TWO));
                }
            }
            case LocalDate date -> {
                // no date lies between consecutive days
                around.add(date.minusDays(1));
                around.add(date.plusDays(1));
            }
            case LocalTime time -> {
                around.add(time.minusMinutes(1));
                around.add(time.plusMinutes(1));
                if (next != null) {
                    around.add(LocalTime.ofNanoOfDay((time.toNanoOfDay() + ((LocalTime) next).toNanoOfDay()) / 2));
                }
            }
            case LocalDateTime moment -> {
                around.add(moment.minusHours(1));
                around.add(moment.plusHours(1));
                if (next != null) {
                    // no zone: measure on the UTC timeline
                    var start = moment.toInstant(ZoneOffset.UTC);
                    var end = ((LocalDateTime) next).toInstant(ZoneOffset.UTC);
                    around.add(LocalDateTime.ofInstant(start.plus(Duration.between(start, end).dividedBy(2)), ZoneOffset.UTC));
                }
            }
            // the generated times with a zone stay hours away from midnight, so a minute either side does not wrap around
            case OffsetTime time -> {
                around.add(time.minusMinutes(1));
                around.add(time.plusMinutes(1));
                if (next != null) {
                    var start = instant(time);
                    around.add(OffsetTime.ofInstant(start.plus(Duration.between(start, instant((OffsetTime) next)).dividedBy(2)), time.getOffset()));
                }
            }
            case ZonedDateTime moment -> {
                around.add(moment.minusHours(1));
                around.add(moment.plusHours(1));
                if (next != null) {
                    var start = moment.toInstant();
                    around.add(start.plus(Duration.between(start, ((ZonedDateTime) next).toInstant()).dividedBy(2)).atZone(moment.getZone()));
                }
            }
            case Duration duration -> {
                around.add(duration.minusHours(1));
                around.add(duration.plusHours(1));
                if (next != null) {
                    around.add(duration.plus(((Duration) next).minus(duration).dividedBy(2)));
                }
            }
            case YearsAndMonths duration -> {
                // no years and months duration lies between consecutive months
                around.add(new YearsAndMonths(duration.months() - 1));
                around.add(new YearsAndMonths(duration.months() + 1));
            }
            default -> throw new IllegalStateException("not an ordered value: " + point);
        }
    }

    // ---- FEEL semantics on concrete values ------------------------------------------------------

    private boolean ruleAccepts(Praeceptum rule, Map<String, ?> input) {
        for (var cell : rule.input().values().entrySet()) {
            if (!accepts(parse(cell.getValue(), cell.getKey()), value(input, cell.getKey()), name -> value(input, name))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a cell compares with another input ({@code > dateE}), which the engine's index cannot see. */
    private boolean isVariable(Praeceptum rule) {
        return rule.input().values().entrySet().stream().anyMatch(cell -> parse(cell.getValue(), cell.getKey()).namesInputs());
    }

    /** What the engine's index sees of a rule: its cells, with those naming inputs taken as "any value". */
    private boolean staticallyAccepts(Praeceptum rule, Map<String, ?> input) {
        for (var cell : rule.input().values().entrySet()) {
            var parsed = parse(cell.getValue(), cell.getKey());
            if (!parsed.namesInputs() && !accepts(parsed, value(input, cell.getKey()), name -> null)) {
                return false;
            }
        }
        return true;
    }

    /** @param inputs the value of each input a cell names */
    private static boolean accepts(UnaryTests cell, Object value, Function<String, Object> inputs) {
        return switch (cell) {
            case UnaryTests.Any any -> true;
            case UnaryTests.Positive positive -> any(positive.tests(), value, inputs) == Boolean.TRUE;
            case UnaryTests.Negated negated -> any(negated.tests(), value, inputs) == Boolean.FALSE;
        };
    }

    /** True if any test is true; otherwise unknown (null) if any is unknown; otherwise false. */
    private static Boolean any(List<Test> tests, Object value, Function<String, Object> inputs) {
        boolean unknown = false;
        for (var test : tests) {
            var outcome = test(resolve(test, inputs), value);
            if (outcome == null) {
                unknown = true;
            } else if (outcome) {
                return true;
            }
        }
        return unknown ? null : false;
    }

    /** The test with each name replaced by that input's value, which may be null (missing). */
    private static Test resolve(Test test, Function<String, Object> inputs) {
        return switch (test) {
            case NullTest n -> n;
            case Equals e -> new Equals(resolve(e.value(), inputs));
            case Compare c -> new Compare(c.operator(), resolve(c.value(), inputs));
            case Range r -> new Range(resolve(r.low(), inputs), r.lowClosed(), resolve(r.high(), inputs), r.highClosed());
        };
    }

    private static Comparable<?> resolve(Comparable<?> value, Function<String, Object> inputs) {
        return value instanceof UnaryTests.Name(var name) ? (Comparable<?>) inputs.apply(name) : value;
    }

    /** FEEL on concrete values: a comparison with a missing value is unknown; null equals only null. */
    private static Boolean test(Test test, Object value) {
        if (value == null) {
            return switch (test) {
                case NullTest n -> true;
                case Equals e -> e.value() == null;
                case Compare c -> null;
                case Range r -> null;
            };
        }
        if (test instanceof Equals e && e.value() == null) {
            return false;
        }
        if (test instanceof Compare c && c.value() == null || test instanceof Range r && (r.low() == null || r.high() == null)) {
            return null;
        }
        // FEEL leaves comparing a time with a zone and one without undefined, equality included
        boolean otherKind = switch (test) {
            case NullTest n -> false;
            case Equals e -> isZoned(e.value()) != isZoned(value);
            case Compare c -> isZoned(c.value()) != isZoned(value);
            case Range r -> isZoned(r.low()) != isZoned(value) || isZoned(r.high()) != isZoned(value);
        };
        if (otherKind) {
            return null;
        }
        return switch (test) {
            case NullTest n -> false;
            case Equals e -> compare(value, e.value()) == 0;
            case Compare c -> switch (c.operator()) {
                case LT -> compare(value, c.value()) < 0;
                case LE -> compare(value, c.value()) <= 0;
                case GT -> compare(value, c.value()) > 0;
                case GE -> compare(value, c.value()) >= 0;
            };
            case Range r -> {
                int low = compare(value, r.low());
                int high = compare(value, r.high());
                yield (r.lowClosed() ? low >= 0 : low > 0) && (r.highClosed() ? high <= 0 : high < 0);
            }
        };
    }

    private static List<Test> tests(UnaryTests cell) {
        return switch (cell) {
            case UnaryTests.Any any -> List.of();
            case UnaryTests.Positive positive -> positive.tests();
            case UnaryTests.Negated negated -> negated.tests();
        };
    }

    private static List<Object> literals(UnaryTests cell) {
        var values = new ArrayList<Object>();
        for (var test : tests(cell)) {
            switch (test) {
                case Equals e -> values.add(e.value());
                case Compare c -> values.add(c.value());
                case Range r -> {
                    values.add(r.low());
                    values.add(r.high());
                }
                case NullTest n -> {
                    // no value
                }
            }
        }
        values.removeIf(UnaryTests.Name.class::isInstance); // known only when evaluating
        return values;
    }

    private UnaryTests parse(String cell, String column) {
        return feelCells ? CellParser.parseFeel(cell, type(column)) : CellParser.parse(cell, type(column));
    }

    private ColumnType type(String column) {
        return types.getOrDefault(column, ColumnType.STRING);
    }

    /** An input value; blank text counts as missing, and a period is compared by its years and months. */
    private static Object value(Map<String, ?> input, String column) {
        return switch (input.get(column)) {
            case String s when s.isBlank() -> null;
            case Period period -> YearsAndMonths.of(period);
            case null, default -> input.get(column);
        };
    }

    /** Values of one kind: times and date-times with a zone by instant, the rest by their natural order. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return switch (a) {
            case OffsetTime time -> instant(time).compareTo(instant((OffsetTime) b));
            case ZonedDateTime moment -> moment.toInstant().compareTo(((ZonedDateTime) b).toInstant());
            default -> ((Comparable) a).compareTo(b);
        };
    }

    private static boolean isZoned(Object value) {
        return value instanceof OffsetTime || value instanceof ZonedDateTime;
    }

    /** A time with an offset as the instant it denotes on 1970-01-01. */
    private static Instant instant(OffsetTime time) {
        return time.atDate(LocalDate.EPOCH).toInstant();
    }

    private static List<Integer> ids(List<Praeceptum> rules) {
        return rules.stream().map(Praeceptum::priority).toList();
    }

    /** Highest priority first; stable, so ties keep rule order. */
    private Comparator<PraeceptaOutput> byPriority() {
        if (outputPriority.isEmpty()) {
            throw new IllegalStateException(hitPolicy + " requires output value lists (outputPriority)");
        }
        Comparator<PraeceptaOutput> comparator = (a, b) -> 0;
        for (var column : outputPriority.entrySet()) {
            var order = column.getValue();
            comparator = comparator.thenComparingInt(output -> rank(order, output.values().get(column.getKey())));
        }
        return comparator;
    }

    private static int rank(List<String> order, String value) {
        int index = order.indexOf(value);
        if (index < 0) {
            throw new IllegalArgumentException("Output value '%s' is not in its output value list %s".formatted(value, order));
        }
        return index;
    }
}
