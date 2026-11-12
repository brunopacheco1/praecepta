/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.math.MathContext;
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
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NotEqual;
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
 * denote, and one without a zone takes the offset of the value it is compared with (DMN 1.5, Table 54),
 * equality included. The engine
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
 * @param lists          the columns whose values are lists (of their type). Their cells follow FEEL's
 *                       {@code list contains} and {@code some x in ? satisfies}: a positive cell holds
 *                       when some element passes one of its tests, or the list is missing and the cell
 *                       has {@code null}; {@code not(...)} holds when the list is there and every element
 *                       fails every test. A list may hold missing elements, for which a literal is false
 *                       and a comparison unknown. A name of such a column in another column's cell asks
 *                       whether the value is in the list.
 * @param bothKinds      whether the inputs of a time or date-time column may have a time zone or not, as
 *                       map-based inputs read them from text; the static analysis then visits both kinds
 *                       of values, whatever {@code zonedColumns} says
 * @param defaultOutput  the output when no rule matches (DMN's default output values), or null if the table
 *                       has none: DMN 1.5, 10.3.2.10, step 2, "if no rules match, if a default output value d is
 *                       specified, DTI=FEEL(d)", before any hit policy or aggregation
 */
public record ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
        Map<String, ColumnType> types, Map<String, String> allowedValues, boolean feelCells, Set<String> zonedColumns,
        Set<String> lists, boolean bothKinds, PraeceptaOutput defaultOutput) {

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
            Map<String, ColumnType> types, Map<String, String> allowedValues, boolean feelCells, Set<String> zonedColumns,
            Set<String> lists, boolean bothKinds) {
        this(rules, hitPolicy, outputPriority, types, allowedValues, feelCells, zonedColumns, lists, bothKinds, null);
    }

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
            Map<String, ColumnType> types, Map<String, String> allowedValues, boolean feelCells, Set<String> zonedColumns,
            Set<String> lists) {
        this(rules, hitPolicy, outputPriority, types, allowedValues, feelCells, zonedColumns, lists, false);
    }

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
            Map<String, ColumnType> types, Map<String, String> allowedValues, boolean feelCells) {
        this(rules, hitPolicy, outputPriority, types, allowedValues, feelCells, Set.of(), Set.of());
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

    /**
     * The first column whose (non-missing) value is outside its declared input values, or, for a list,
     * that holds a (non-missing) element outside them; null if there is none.
     */
    public String invalidColumn(Map<String, ?> input) {
        for (var declared : allowedValues.entrySet()) {
            var allowed = declared(declared.getKey());
            var value = value(input, declared.getKey());
            var values = value instanceof List<?> elements ? elements : Collections.singletonList(value);
            for (var element : values) {
                if (element != null && !accepts(allowed, element, name -> null)) {
                    return declared.getKey();
                }
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
        if (matches.isEmpty() && defaultOutput != null) {
            return new Outcome.Hits(List.of(defaultOutput)); // whatever the hit policy
        }
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
     * DMN COLLECT with an aggregator over a single numeric output column, as DMN 1.5, 10.3.2.10 evaluates it.
     * No match is step 2, before any aggregation: the default output's value, if the table has a default
     * output (null if that output has no default), else null, which is no result at all (empty), whatever the
     * aggregator, COUNT included. Otherwise (step 3b) FEEL's {@code sum}, {@code min}, {@code max} or {@code count}
     * of the matching rules' outputs: the first three are null when an output is missing (a literal {@code null},
     * or a computed output that names a missing input or divides by zero), since null is outside their parameters'
     * domain (10.3.4.4, 10.3.2.16); COUNT counts the matching rules, whatever their outputs.
     */
    public Optional<Aggregate> aggregate(Map<String, ?> input, String output, Aggregator aggregator) {
        if (hitPolicy != HitPolicy.COLLECT) {
            throw new IllegalStateException("DMN only allows aggregation with COLLECT");
        }
        var matched = matches(input);
        if (matched.isEmpty()) {
            if (defaultOutput == null) {
                return Optional.empty();
            }
            var value = defaultOutput.values().get(output);
            return Optional.of(new Aggregate(value == null ? null : new BigDecimal(value)));
        }
        var texts = matched.stream().map(rule -> output(rule, input).values().get(output)).toList();
        if (aggregator == Aggregator.COUNT) {
            return Optional.of(new Aggregate(BigDecimal.valueOf(texts.size())));
        }
        if (texts.stream().anyMatch(Objects::isNull)) {
            return Optional.of(new Aggregate(null));
        }
        var values = texts.stream().map(BigDecimal::new).toList();
        return Optional.of(new Aggregate(switch (aggregator) {
            case SUM -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case MIN -> values.stream().min(Comparator.naturalOrder()).orElseThrow();
            case MAX -> values.stream().max(Comparator.naturalOrder()).orElseThrow();
            case COUNT -> throw new IllegalStateException("counted above");
        }));
    }

    /** A COLLECT aggregation's result: its value, null when it is missing. */
    public record Aggregate(BigDecimal value) {
    }

    public enum Aggregator { COUNT, SUM, MIN, MAX }

    /**
     * The rule's outputs for this input: an output that names an input takes the text of the input's
     * value (null if missing), as plain outputs are text.
     */
    public PraeceptaOutput output(Praeceptum rule, Map<String, ?> input) {
        if (rule.output().references().isEmpty() && rule.output().expressions().isEmpty()) {
            return rule.output();
        }
        var values = new HashMap<>(rule.output().values());
        rule.output().references().forEach((output, column) -> {
            var value = value(input, column);
            values.put(output, value == null ? null : text(value));
        });
        rule.output().expressions().forEach((output, expression) -> {
            var value = evaluate(OutputExpression.parse(expression), name -> value(input, name));
            values.put(output, switch (value) {
                case null -> null;
                case BigDecimal number -> number.compareTo(BigDecimal.ZERO) == 0 ? "0" : number.stripTrailingZeros().toPlainString();
                default -> value.toString();
            });
        });
        return new PraeceptaOutput(values);
    }

    /**
     * The text of an input's value, which must read back as the same value: a time or date-time with a
     * zone as the same instant in the same zone (one in a zone's daylight saving overlap included). If it
     * does not, the text is marked, so that it differs from whatever the engine gives.
     */
    private static String text(Object value) {
        var text = FeelLiterals.text(value);
        var readBack = switch (value) {
            case OffsetTime time -> FeelTemporals.parseTime(text);
            case ZonedDateTime dateTime -> FeelTemporals.parseDateTime(text);
            default -> value;
        };
        return readBack.equals(value) ? text : "not read back as " + value + ": " + text;
    }

    /**
     * An output expression on concrete values, as FEEL defines it: a missing operand gives null, {@code +}
     * joins strings or adds numbers, a division by zero gives null and every result has 34 digits
     * (DMN 1.5, Tables 57, 59 and 60). Exponents are whole numbers here.
     */
    private static Object evaluate(OutputExpression expression, Function<String, Object> inputs) {
        return switch (expression) {
            case OutputExpression.Literal literal -> literal.value();
            case OutputExpression.Name name -> inputs.apply(name.name());
            case OutputExpression.Negation negation -> {
                var operand = (BigDecimal) evaluate(negation.operand(), inputs);
                yield operand == null ? null : BigDecimal.ZERO.subtract(operand);
            }
            case OutputExpression.Arithmetic arithmetic -> {
                var a = evaluate(arithmetic.left(), inputs);
                var b = evaluate(arithmetic.right(), inputs);
                if (a == null || b == null) {
                    yield null;
                }
                if (a instanceof String text) {
                    yield text.concat((String) b);
                }
                var x = (BigDecimal) a;
                var y = (BigDecimal) b;
                yield switch (arithmetic.operator()) {
                    case ADD -> x.add(y).round(MathContext.DECIMAL128);
                    case SUBTRACT -> x.subtract(y).round(MathContext.DECIMAL128);
                    case MULTIPLY -> x.multiply(y).round(MathContext.DECIMAL128);
                    case DIVIDE -> y.compareTo(BigDecimal.ZERO) == 0 ? null : x.divide(y, MathContext.DECIMAL128);
                    case POWER -> power(x, y.intValueExact());
                };
            }
        };
    }

    /**
     * A number to a whole power, as FEEL defines it: multiplied out exactly, then rounded once to 34 digits;
     * a negative power is one over the positive one, and zero to a negative power is null.
     */
    private static BigDecimal power(BigDecimal x, int n) {
        if (n >= 0) {
            return x.pow(n).round(MathContext.DECIMAL128);
        }
        return x.signum() == 0 ? null : BigDecimal.ONE.divide(x.pow(-n), MathContext.DECIMAL128);
    }

    // ---- static analysis, by brute force --------------------------------------------------------

    /** An expected validation finding: the kind name and the rule ids involved, in rule order. */
    public record Finding(String kind, List<Integer> ruleIds) {
    }

    /**
     * Expected overlap and reachability findings. Every input region is visited by evaluating every
     * combination of {@link #representativeValues representative values}; invalid inputs are skipped,
     * since evaluation rejects them before matching. As the rules that the index sees an input match are those
     * that every column's value is accepted by, the combinations are visited as the sets of rules they leave,
     * column by column, each set once ({@link #staticMatchSets}).
     */
    public Set<Finding> expectedFindings(List<String> columns) {
        var findings = new LinkedHashSet<Finding>();
        var selected = new HashSet<Praeceptum>();
        for (var set : staticMatchSets(columns)) {
            // rules with cells naming inputs match inputs known only when evaluating: they never overlap,
            // and never hide another rule, but they are unreachable if their other cells are covered
            var seen = set.stream().mapToObj(rules::get).toList();
            var matches = seen.stream().filter(rule -> !isVariable(rule)).toList();
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

    /**
     * Whether the table is complete (DMN 1.5, 8.2.4: "its rules cover all combinations of expected input values"):
     * whether every valid input, with a missing value in any column too, since evaluation accepts one, matches some
     * rule.
     */
    public boolean everyInputMatches(List<String> columns) {
        return representativeInputs(columns, true).stream()
                .filter(input -> invalidColumn(input) == null)
                .allMatch(input -> !matches(input).isEmpty());
    }

    /**
     * Whether the table is complete through the rules the engine's analysis sees: those without a cell that names
     * an input or tests a list, whose matches are known only when evaluating. If so, it is complete.
     */
    public boolean everyInputMatchesAnAnalysedRule(List<String> columns) {
        var analysed = rules.stream().filter(rule -> !isVariable(rule)).toList();
        return representativeInputs(columns, true).stream()
                .filter(input -> invalidColumn(input) == null)
                .allMatch(input -> analysed.stream().anyMatch(rule -> ruleAccepts(rule, input)));
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
        if (lists.contains(column)) {
            var values = new ArrayList<Object>();
            representativeLists(column, withMissing, values::add);
            return values;
        }
        return representativeElements(column, withMissing, bothKinds);
    }

    /**
     * Lists that visit every region the cells of a list column distinguish, given to {@code sink}: every list of
     * at most {@code n + 2} distinct elements drawn from its representative values (both with and without a time
     * zone, as a list may mix them) that its declared values allow, one per answer the cells give them, where
     * {@code n} is the number of different sets of those elements that rule out a {@code not(...)} cell (leave
     * its inside true or unknown); with {@code withMissing}, a missing element among them, and a missing list.
     *
     * <p>Lists of more elements add nothing: two elements to which every cell gives the same three-valued
     * answer are alike in any list, since a cell joins its answers over the elements with {@code or}; and if
     * some list matches a rule and none of a set of others (or two rules, or none at all), so does one that
     * keeps, of its elements, one that passes that rule's positive cell, one that passes another's, and one
     * that rules out each {@code not(...)} cell that must not match (one per set of such elements at most),
     * for removing an element never makes a positive cell match or a {@code not(...)} cell fail.
     */
    private void representativeLists(String column, boolean withMissing, Consumer<Object> sink) {
        var cells = rules.stream().map(rule -> parse(rule.input().values().get(column), column)).toList();
        var allowed = allowedValues.containsKey(column) ? CellParser.parse(allowedValues.get(column), type(column)) : null;
        var bySignature = new LinkedHashMap<List<Boolean>, Object>();
        for (var element : representativeElements(column, withMissing, true)) {
            if (element == null || allowed == null || accepts(allowed, single(element), name -> null)) {
                bySignature.putIfAbsent(cells.stream().map(cell -> some(tests(cell), Collections.singletonList(single(element)))).toList(),
                        element);
            }
        }
        var pool = new ArrayList<>(bySignature.values());
        var rulingOut = new HashSet<List<Boolean>>();
        for (var cell : cells) {
            if (cell instanceof UnaryTests.Negated) {
                rulingOut.add(pool.stream().map(e -> some(tests(cell), Collections.singletonList(single(e))) != Boolean.FALSE).toList());
            }
        }
        subLists(pool, 0, new ArrayList<>(), Math.min(rulingOut.size() + 2, pool.size()), sink);
        if (withMissing) {
            sink.accept(null);
        }
    }

    /** Every list of at most {@code size} elements of {@code pool} from {@code from} on, in pool order, after {@code prefix}. */
    private static void subLists(List<Object> pool, int from, List<Object> prefix, int size, Consumer<Object> sink) {
        sink.accept(Collections.unmodifiableList(new ArrayList<>(prefix)));
        if (prefix.size() == size) {
            return;
        }
        for (int i = from; i < pool.size(); i++) {
            prefix.add(pool.get(i));
            subLists(pool, i + 1, prefix, size, sink);
            prefix.removeLast();
        }
    }

    /** The representative single values of a column, of both kinds of time if {@code eitherKind}. */
    private List<Object> representativeElements(String column, boolean withMissing, boolean eitherKind) {
        var type = type(column);
        var cells = new ArrayList<UnaryTests>();
        rules.forEach(rule -> cells.add(parse(rule.input().values().get(column), column)));
        if (allowedValues.containsKey(column)) {
            // a list column's declared values test single elements
            var declared = allowedValues.get(column);
            cells.add(lists.contains(column) ? CellParser.parse(declared, type) : parse(declared, column));
        }
        var values = new ArrayList<Object>();
        switch (type) {
            case BOOLEAN -> values.addAll(List.of(false, true)); // the whole domain
            case STRING -> values.addAll(stringRepresentatives(cells));
            // years and months durations as the Period a getter returns
            case YEAR_MONTH_DURATION -> orderedRepresentatives(type, cells, false).forEach(v -> values.add(((YearsAndMonths) v).toPeriod()));
            // a column's times take a zone or none, as its getter does, or either if read from text
            case TIME, DATE_TIME -> {
                if (eitherKind || !zonedColumns.contains(column)) {
                    values.addAll(orderedRepresentatives(type, cells, false));
                }
                if (eitherKind || zonedColumns.contains(column)) {
                    values.addAll(orderedRepresentatives(type, cells, true));
                }
            }
            default -> values.addAll(orderedRepresentatives(type, cells, false));
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
            var options = distinctValues(column, withMissing);
            var next = new ArrayList<Map<String, Object>>();
            for (var shorter : inputs) {
                for (var option : options) {
                    var input = new HashMap<>(shorter);
                    input.put(column, option);
                    next.add(input);
                }
            }
            inputs = next;
        }
        return inputs;
    }

    /**
     * The {@link #representativeValues representative values} of a column that its declared values allow, one
     * for each set of rules whose cell in the column accepts it (a cell naming another input accepting any): the
     * rules an input matches, apart from those with such cells, depend on nothing else, so two values in the same
     * set are alike to every rule, and an input that combines them with the same values of the other columns
     * matches the same rules. This keeps the product of several list columns small.
     */
    private List<Object> distinctValues(String column, boolean withMissing) {
        return new ArrayList<>(bySignature(column, withMissing).values());
    }

    /**
     * Every set of rules that the index sees some valid representative input match (cells naming other inputs
     * taken as any value), as rule indices: the sets each column's {@link #distinctValues} leave, intersected
     * column by column.
     */
    private Set<BitSet> staticMatchSets(List<String> columns) {
        var all = new BitSet();
        all.set(0, rules.size());
        Set<BitSet> sets = new LinkedHashSet<>(List.of(all));
        for (var column : columns) {
            var accepting = bySignature(column, true).keySet().stream().map(signature -> {
                var set = new BitSet();
                for (int r = 0; r < signature.size(); r++) {
                    set.set(r, signature.get(r));
                }
                return set;
            }).toList();
            var next = new LinkedHashSet<BitSet>();
            for (var set : sets) {
                for (var accepted : accepting) {
                    var both = (BitSet) set.clone();
                    both.and(accepted);
                    next.add(both);
                }
            }
            sets = next;
        }
        return sets;
    }

    /** The {@link #distinctValues} of a column, each by the rules whose cell in the column accepts it. */
    private Map<List<Boolean>, Object> bySignature(String column, boolean withMissing) {
        var bySignature = new LinkedHashMap<List<Boolean>, Object>();
        Consumer<Object> keep = option -> {
            var alone = new HashMap<String, Object>();
            alone.put(column, option);
            if (invalidColumn(alone) == null) {
                bySignature.putIfAbsent(rules.stream().map(rule -> cellAccepts(rule, column, alone)).toList(), option);
            }
        };
        if (lists.contains(column)) {
            representativeLists(column, withMissing, keep); // many lists: kept one by one
        } else {
            representativeElements(column, withMissing, bothKinds).forEach(keep);
        }
        return bySignature;
    }

    /** Whether a rule's cell in a column accepts the input's value there; a cell naming another input accepts any. */
    private boolean cellAccepts(Praeceptum rule, String column, Map<String, ?> input) {
        var parsed = parse(rule.input().values().get(column), column);
        if (parsed.namesInputs()) {
            return true;
        }
        var value = value(input, column);
        return lists.contains(column) ? acceptsList(parsed, (List<?>) value) : accepts(parsed, value, name -> null);
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
     * The values the cells mention, the points around each and between neighbours, in order. For times and
     * date-times, these are values without a time zone, which FEEL compares with every literal by local
     * time: a literal with a zone counts as its local time. Values with a zone if {@code zoned}, see
     * {@link #zonedRepresentatives}.
     */
    private static Collection<Object> orderedRepresentatives(ColumnType type, List<UnaryTests> cells, boolean zoned) {
        if (zoned) {
            return zonedRepresentatives(type, cells);
        }
        var points = new TreeSet<Object>(ReferenceMatcher::compare);
        cells.forEach(cell -> literals(cell).stream().map(ReferenceMatcher::withoutZone).forEach(points::add));
        var sorted = new ArrayList<>(points);
        var around = new TreeSet<Object>(ReferenceMatcher::compare);
        around.addAll(sorted);
        if (sorted.isEmpty()) {
            around.add(switch (type) {
                case NUMBER -> BigDecimal.ZERO;
                case TIME -> LocalTime.NOON;
                case DATE_TIME -> LocalDateTime.of(2025, 1, 1, 0, 0);
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

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_DAY = 86_400 * NANOS_PER_SECOND;
    /** A zone offset is a whole number of seconds from -18:00 to +18:00. */
    private static final long MAX_OFFSET_SECONDS = 18 * 3600;

    /**
     * Times or date-times with a time zone that visit every region the cells distinguish among them. FEEL
     * compares such a value with a literal with a zone by instant, and with one without by local time, so a
     * region is a set of instants (on or between the literals with a zone) together with a set of local
     * times (on or between those without). A value is an instant and an offset, and its local time their
     * sum; for each offset, every region that has a value at it has one whose instant, or local time, is
     * on or a nanosecond beside a literal, and a region with values has one at its lowest offset, which is
     * -18:00 or among the first that bring a literal's local time past a literal's instant. So those instants at
     * those offsets are tried, and one value is kept for each set of cells that accept it: to the cells,
     * which do not name other inputs where the analysis looks, values accepted by the same ones are alike.
     */
    private static Collection<Object> zonedRepresentatives(ColumnType type, List<UnaryTests> cells) {
        var instants = new TreeSet<Long>();
        var localTimes = new TreeSet<Long>();
        var written = new ArrayList<Object>();
        cells.forEach(cell -> written.addAll(literals(cell)));
        for (var literal : written) {
            (isZoned(literal) ? instants : localTimes).add(nanos(literal));
        }
        var localBounds = new TreeSet<>(localTimes);
        if (type == ColumnType.TIME) {
            localBounds.add(0L); // a time's local time stays within its day
            localBounds.add(NANOS_PER_DAY);
        }
        var offsets = new TreeSet<Long>(List.of(-MAX_OFFSET_SECONDS, 0L, MAX_OFFSET_SECONDS));
        for (long local : localBounds) {
            for (long instant : instants) {
                long seconds = Math.floorDiv(local - instant, NANOS_PER_SECOND);
                offsets.addAll(List.of(seconds, seconds + 1, seconds + 2));
            }
        }
        offsets.removeIf(seconds -> Math.abs(seconds) > MAX_OFFSET_SECONDS);
        var bySignature = new HashMap<List<Boolean>, Object>();
        var values = new ArrayList<Object>();
        for (long seconds : offsets) {
            var candidates = new TreeSet<Long>(instants);
            localBounds.forEach(local -> candidates.add(local - seconds * NANOS_PER_SECOND));
            if (candidates.isEmpty()) {
                candidates.add(type == ColumnType.TIME ? NANOS_PER_DAY / 2 : nanos(LocalDateTime.of(2025, 1, 1, 0, 0)));
            }
            for (long candidate : candidates) {
                for (long instant = candidate - 1; instant <= candidate + 1; instant++) {
                    var value = zoned(type, instant, seconds);
                    if (value != null && bySignature.putIfAbsent(signature(value, cells), value) == null) {
                        values.add(value);
                    }
                }
            }
        }
        return values;
    }

    /** Which of the cells accept a value. */
    private static List<Boolean> signature(Object value, List<UnaryTests> cells) {
        return cells.stream().map(cell -> accepts(cell, value, name -> null)).toList();
    }

    /**
     * A literal's place in time, in nanoseconds: an instant for one with a zone (since the epoch, or for a
     * time since midnight at offset zero), a local time for one without (as if at offset zero).
     */
    private static long nanos(Object literal) {
        return switch (literal) {
            case LocalTime time -> time.toNanoOfDay();
            case OffsetTime time -> time.toLocalTime().toNanoOfDay() - time.getOffset().getTotalSeconds() * NANOS_PER_SECOND;
            case LocalDateTime moment -> moment.toEpochSecond(ZoneOffset.UTC) * NANOS_PER_SECOND + moment.getNano();
            case ZonedDateTime moment -> moment.toEpochSecond() * NANOS_PER_SECOND + moment.getNano();
            default -> throw new IllegalStateException("not a time or date-time: " + literal);
        };
    }

    /** The time or date-time at an instant ({@link #nanos}) and an offset; null for a time whose local time leaves its day. */
    private static Object zoned(ColumnType type, long instant, long offsetSeconds) {
        var offset = ZoneOffset.ofTotalSeconds((int) offsetSeconds);
        long local = instant + offsetSeconds * NANOS_PER_SECOND;
        if (type == ColumnType.TIME) {
            return local < 0 || local >= NANOS_PER_DAY ? null : OffsetTime.of(LocalTime.ofNanoOfDay(local), offset);
        }
        return ZonedDateTime.of(LocalDateTime.ofEpochSecond(Math.floorDiv(local, NANOS_PER_SECOND),
                (int) Math.floorMod(local, NANOS_PER_SECOND), ZoneOffset.UTC), offset);
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
            // the generated times with a zone stay hours away from their local midnight, so a minute either side does not wrap
            // around; a point between two at different offsets takes an offset that holds it without wrapping around
            case OffsetTime time -> {
                around.add(time.minusMinutes(1));
                around.add(time.plusMinutes(1));
                if (next != null) {
                    var start = instant(time);
                    var between = start.plus(Duration.between(start, instant((OffsetTime) next)).dividedBy(2));
                    around.add(atInstant(between, time.getOffset(), ((OffsetTime) next).getOffset()));
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
            var parsed = parse(cell.getValue(), cell.getKey());
            var value = value(input, cell.getKey());
            boolean accepted = lists.contains(cell.getKey())
                    ? acceptsList(parsed, (List<?>) value)
                    : accepts(parsed, value, name -> value(input, name));
            if (!accepted) {
                return false;
            }
        }
        return true;
    }

    /**
     * A list column's cell, as FEEL evaluates {@code list contains(?, v) or some x in ? satisfies x < v or ? = null}
     * and its {@code not(...)}: on a missing list every element test is unknown, so only {@code null}
     * holds and a negated cell is unknown. On a list that is there, {@code ? = null} is false, and each
     * element test is applied to every element, a missing one included: {@code list contains(?, v)} is
     * {@code null = v} for it, false unless {@code v} is null, and {@code some x in ? satisfies x < v} is
     * unknown for it (DMN 1.5, Tables 49, 50 and 75).
     */
    private static boolean acceptsList(UnaryTests cell, List<?> list) {
        if (cell instanceof UnaryTests.Any) {
            return true;
        }
        Boolean some;
        if (list == null) {
            some = tests(cell).stream().anyMatch(NullTest.class::isInstance) ? Boolean.TRUE : null;
        } else {
            some = some(tests(cell), list);
        }
        return cell instanceof UnaryTests.Negated ? some == Boolean.FALSE : some == Boolean.TRUE;
    }

    /**
     * Whether some element of a list passes one of the tests, with FEEL's three-valued {@code or}: true if
     * one passes; else unknown (null) if one is unknown; else false. {@code null} ({@code ? = null}) tests
     * the list, which is there, so it is false.
     */
    private static Boolean some(List<Test> tests, List<?> list) {
        Boolean some = Boolean.FALSE;
        for (var test : tests) {
            for (var element : list) {
                var outcome = test instanceof NullTest ? Boolean.FALSE : test(test, element);
                if (outcome == Boolean.TRUE) {
                    return Boolean.TRUE;
                }
                if (outcome == null) {
                    some = null;
                }
            }
        }
        return some;
    }

    /** Whether the engine's index cannot see what a rule matches: a cell compares with another input ({@code > dateE}). */
    private boolean isVariable(Praeceptum rule) {
        return rule.input().values().entrySet().stream().anyMatch(cell -> parse(cell.getValue(), cell.getKey()).namesInputs());
    }

    /** What the engine's index sees of a rule: its cells, with those naming inputs taken as "any value". */
    private boolean staticallyAccepts(Praeceptum rule, Map<String, ?> input) {
        for (var cell : rule.input().values().entrySet()) {
            var parsed = parse(cell.getValue(), cell.getKey());
            var value = value(input, cell.getKey());
            boolean accepted = lists.contains(cell.getKey()) ? acceptsList(parsed, (List<?>) value) : accepts(parsed, value, name -> null);
            if (!parsed.namesInputs() && !accepted) {
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
            // a name of an input that holds a list: whether the value is in it (FEEL's ? in list)
            var outcome = test instanceof Equals(UnaryTests.Name(var name)) && inputs.apply(name) instanceof List<?> list
                    ? in(value, list)
                    : test(resolve(test, inputs), value);
            if (outcome == null) {
                unknown = true;
            } else if (outcome) {
                return true;
            }
        }
        return unknown ? null : false;
    }

    /**
     * Whether a value is in a list, as FEEL's {@code ? in list}, which is {@code list contains(list, ?)}
     * (DMN 1.5, Table 55): whether some element equals it. A missing value is in a list that holds a missing
     * element, and in no other.
     */
    private static Boolean in(Object value, List<?> list) {
        if (value == null) {
            return list.stream().anyMatch(Objects::isNull);
        }
        return some(list.stream().<Test>map(element -> new Equals((Comparable<?>) element)).toList(), List.of(value));
    }

    /** The test with each name replaced by that input's value, which may be null (missing). */
    private static Test resolve(Test test, Function<String, Object> inputs) {
        return switch (test) {
            case NullTest n -> n;
            case UnaryTests.NullElement n -> n;
            case NotEqual n -> new NotEqual(resolve(n.test(), inputs));
            case Equals e -> new Equals(resolve(e.value(), inputs));
            case Compare c -> new Compare(c.operator(), resolve(c.value(), inputs));
            case Range r -> new Range(resolve(r.low(), inputs), r.lowClosed(), resolve(r.high(), inputs), r.highClosed());
        };
    }

    private static Comparable<?> resolve(Comparable<?> value, Function<String, Object> inputs) {
        return value instanceof UnaryTests.Name(var name) ? (Comparable<?>) inputs.apply(name) : value;
    }

    /**
     * FEEL on concrete values: a comparison with a missing value is unknown; null equals only null; {@code !=} is
     * {@code not(=)} (DMN 1.5, 10.3.2.15), so it holds for a missing value unless it is {@code != null}.
     */
    private static Boolean test(Test test, Object value) {
        if (test instanceof NotEqual(var negated)) {
            var equal = test(negated, value);
            return equal == null ? null : !equal;
        }
        if (value == null) {
            return switch (test) {
                case NullTest n -> true;
                case UnaryTests.NullElement n -> true;
                case Equals e -> e.value() == null;
                case Compare c -> null;
                case Range r -> null;
                case NotEqual n -> throw new IllegalStateException("decided above");
            };
        }
        if (test instanceof Equals e && e.value() == null) {
            return false;
        }
        if (test instanceof Compare c && c.value() == null || test instanceof Range r && (r.low() == null || r.high() == null)) {
            return null;
        }
        return switch (test) {
            case NullTest n -> false;
            case UnaryTests.NullElement n -> false;
            case NotEqual n -> throw new IllegalStateException("decided above");
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
                case NotEqual n -> values.addAll(UnaryTests.values(n));
                case NullTest n -> {
                    // no value
                }
                case UnaryTests.NullElement n -> {
                    // no value
                }
            }
        }
        values.removeIf(UnaryTests.Name.class::isInstance); // known only when evaluating
        return values;
    }

    /** Cells parsed before, by text, type and syntax: the brute force evaluates each many times. */
    private static final Map<List<Object>, UnaryTests> PARSED = new ConcurrentHashMap<>();

    /** A column's declared input values, which test single values (a list's elements) in Praecepta's syntax. */
    private UnaryTests declared(String column) {
        var type = type(column);
        var text = allowedValues.get(column);
        return PARSED.computeIfAbsent(Arrays.asList(text, type, "declared"), key -> CellParser.parse(text, type));
    }

    private UnaryTests parse(String cell, String column) {
        var type = type(column);
        boolean list = lists.contains(column);
        return PARSED.computeIfAbsent(Arrays.asList(cell, type, feelCells, list), key -> {
            if (!feelCells) {
                return CellParser.parse(cell, type);
            }
            return list ? CellParser.parseFeelList(cell, type) : CellParser.parseFeel(cell, type);
        });
    }

    private ColumnType type(String column) {
        return types.getOrDefault(column, ColumnType.STRING);
    }

    /**
     * An input value; blank text counts as missing, and a period is compared by its years and months. A
     * list's elements are read the same way.
     */
    private static Object value(Map<String, ?> input, String column) {
        return input.get(column) instanceof List<?> list ? list.stream().map(ReferenceMatcher::single).toList() : single(input.get(column));
    }

    private static Object single(Object value) {
        return switch (value) {
            case String s when s.isBlank() -> null;
            case Period period -> YearsAndMonths.of(period);
            case null, default -> value;
        };
    }

    /**
     * FEEL's order (DMN 1.5, Table 54): times and date-times by the instant they denote, where one
     * without a time zone "uses the timezone offset of the other input"; two without a zone by their natural
     * order; strings lexicographically by character, a Unicode code point (Table 54; 10.3.2.3.2), not by
     * UTF-16 code unit as {@link String#compareTo} does; values of the other types by their natural order.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return switch (a) {
            case OffsetTime time -> instant(time)
                    .compareTo(instant(b instanceof LocalTime local ? local.atOffset(time.getOffset()) : (OffsetTime) b));
            case LocalTime time when b instanceof OffsetTime other -> instant(time.atOffset(other.getOffset())).compareTo(instant(other));
            case ZonedDateTime moment -> moment.toInstant()
                    .compareTo(b instanceof LocalDateTime local ? local.toInstant(moment.getOffset()) : ((ZonedDateTime) b).toInstant());
            case LocalDateTime moment when b instanceof ZonedDateTime other -> moment.toInstant(other.getOffset()).compareTo(other.toInstant());
            case String text -> Arrays.compare(text.codePoints().toArray(), ((String) b).codePoints().toArray());
            default -> ((Comparable) a).compareTo(b);
        };
    }

    /** A literal as a value without a time zone is compared with it: by its local time. */
    private static Object withoutZone(Object value) {
        return switch (value) {
            case OffsetTime time -> time.toLocalTime();
            case ZonedDateTime moment -> moment.toLocalDateTime();
            default -> value;
        };
    }

    private static boolean isZoned(Object value) {
        return value instanceof OffsetTime || value instanceof ZonedDateTime;
    }

    /** The time at the first offset whose local time on 1970-01-01 holds {@code instant}, so that it denotes that instant. */
    private static OffsetTime atInstant(Instant instant, ZoneOffset... offsets) {
        for (var offset : offsets) {
            var time = OffsetTime.ofInstant(instant, offset);
            if (instant(time).equals(instant)) {
                return time;
            }
        }
        throw new IllegalStateException("no offset holds " + instant);
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
