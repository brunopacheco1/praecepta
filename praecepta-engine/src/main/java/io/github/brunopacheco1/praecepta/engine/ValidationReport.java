/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Static analysis of a decision table, computed when the engine is built. It is exact: every issue
 * corresponds to inputs that actually occur, and every input region is examined (gaps, and pairs of
 * overlapping rules or of rules with conflicting outputs, are each capped at 100 entries), except where
 * the analysis cannot decide, which it leaves unreported rather than report wrongly: rules whose cells
 * compare with another input are judged only as rules that may be unreachable, and in a table with several
 * list columns a rule, or the table's gaps, go unreported when a list column can leave more than 1024
 * different sets of rules.
 *
 * <p>A {@link DecisionGraph} reports what is wrong with its decision model the same way: cycles of required decisions,
 * missing requirements and type mismatches ({@link Kind#DECISION_CYCLE}, {@link Kind#MISSING_REQUIREMENT},
 * {@link Kind#TYPE_MISMATCH}), beside each of its tables' own reports.
 *
 * @param issues findings, most severe first
 */
public record ValidationReport(List<Issue> issues) {

    public ValidationReport {
        issues = List.copyOf(issues);
    }

    public enum Severity {
        WARNING,
        ERROR;

        /**
         * Reads a {@code failOn} setting, as every build-tool and framework integration does: {@code ERROR},
         * {@code WARNING} or {@code NONE}, in any case and with surrounding whitespace ignored.
         *
         * @return the severity, or null for {@code NONE}, which fails on no validation issue
         * @throws IllegalArgumentException for any other value, null and blank included; its message,
         *                                  {@code must be ERROR, WARNING or NONE, not <value>}, follows the
         *                                  setting's name as the tool spells it
         */
        public static Severity failOn(String value) {
            var text = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
            return switch (text) {
                case "NONE" -> null;
                case "ERROR" -> ERROR;
                case "WARNING" -> WARNING;
                default -> throw new IllegalArgumentException("must be ERROR, WARNING or NONE, not " + value);
            };
        }
    }

    public enum Kind {
        /** No input ever selects this rule: earlier or higher-priority rules always win (FIRST, PRIORITY). */
        UNREACHABLE_RULE(Severity.WARNING),
        /**
         * Two rules that some inputs match together (UNIQUE): evaluating those inputs raises a violation. At most 100
         * pairs are listed, the first in rule order: rules that each name another value of a list column all overlap.
         */
        OVERLAPPING_RULES(Severity.ERROR),
        /** Two rules with different outputs that some inputs match together (ANY): a violation. At most 100 pairs are listed. */
        CONFLICTING_OUTPUTS(Severity.ERROR),
        /**
         * Declared input values that no rule matches (evaluation returns the default output, or none). Only reported
         * when every string column the rules constrain declares its input values; at most 100 gaps are
         * listed.
         */
        GAP(Severity.WARNING),
        /**
         * A default output that no input can reach: the table is complete, as every input, with a missing value in
         * any column too, matches some rule, so the default is never given. DMN 1.5, 10.3.2.10: "A complete decision
         * table SHALL NOT specify a default output value." A column holding lists is searched as every list it
         * takes, a missing one and ones holding missing elements included. Rules that compare with another input are
         * left out of the search, as from the other analyses, so a table that is complete only through them is not
         * reported.
         */
        UNREACHABLE_DEFAULT_OUTPUT(Severity.ERROR),
        /**
         * Decisions of a decision model that require one another in a cycle, directly or indirectly ({@code DecisionGraph}):
         * DMN 1.5, 6.3.7, "a Decision element SHALL not require itself, directly or indirectly". None of them, nor any
         * decision that requires one of them, can be evaluated. The detail names the cycle.
         */
        DECISION_CYCLE(Severity.ERROR),
        /**
         * A decision of a decision model that requires a decision or an input data the model does not have, or one of
         * the other kind, or whose logic reads a name none of its information requirements provides (DMN 1.5, 6.3.13, and
         * 7.3.4: "each variable that is referenced by a decision's expression must be associated with a required decision,
         * required input data, or required knowledge"). It cannot be evaluated, nor can any decision that requires it.
         * The detail names the decision and what is missing.
         */
        MISSING_REQUIREMENT(Severity.ERROR),
        /**
         * A value one element of a decision model gives a decision that cannot fit where it goes: a value of another type
         * than the decision table input it feeds (a string into a number column, a list into a column of single values),
         * a path into a value that has no such component, operands a literal expression cannot combine, or a literal
         * expression's value of another type than its decision declares. The decision cannot be evaluated, nor can any
         * decision that requires it, except in the last case, where DMN 1.5 defines the decision's value: null, as the
         * value does not conform to the declared type (10.3.2.9.4). The detail names the decision, the value and both
         * types.
         */
        TYPE_MISMATCH(Severity.ERROR);

        private final Severity severity;

        Kind(Severity severity) {
            this.severity = severity;
        }

        public Severity severity() {
            return severity;
        }
    }

    /**
     * @param kind    what was found
     * @param ruleIds identifiers ({@code Praeceptum.priority}) of the rules involved, in rule order
     *                (empty for gaps and for an unreachable default output)
     * @param detail  for gaps, the uncovered region, e.g. {@code country = "FR", age in (18..21)};
     *                columns not mentioned may take any declared value; for the issues of a decision model
     *                ({@link Kind#DECISION_CYCLE}, {@link Kind#MISSING_REQUIREMENT}, {@link Kind#TYPE_MISMATCH}), what is
     *                wrong, naming the decision. Null otherwise.
     * @param example for gaps, one concrete uncovered input (columns not mentioned: any value).
     *                Empty otherwise.
     */
    public record Issue(Kind kind, List<Integer> ruleIds, String detail, Map<String, Object> example) {

        public Issue {
            ruleIds = List.copyOf(ruleIds);
            example = Collections.unmodifiableMap(new LinkedHashMap<>(example));
        }

        public Issue(Kind kind, List<Integer> ruleIds) {
            this(kind, ruleIds, null, Map.of());
        }

        public Severity severity() {
            return kind.severity();
        }

        @Override
        public String toString() {
            return switch (kind) {
                case UNREACHABLE_RULE -> "WARNING rule %s can never be selected".formatted(ruleIds.getFirst());
                case OVERLAPPING_RULES -> "ERROR rules %s both match some inputs (UNIQUE)".formatted(ruleIds);
                case CONFLICTING_OUTPUTS -> "ERROR rules %s both match some inputs but differ in outputs (ANY)".formatted(ruleIds);
                case GAP -> "WARNING no rule matches " + detail;
                case UNREACHABLE_DEFAULT_OUTPUT -> "ERROR every input matches a rule, so the default output is never given "
                        + "(a complete table declares no default output: DMN 1.5, 10.3.2.10)";
                case DECISION_CYCLE, MISSING_REQUIREMENT, TYPE_MISMATCH -> "ERROR " + detail;
            };
        }
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    /** Whether any issue is at least as severe as {@code severity}. */
    public boolean hasIssuesAtLeast(Severity severity) {
        return issues.stream().anyMatch(issue -> issue.severity().compareTo(severity) >= 0);
    }

    public List<Issue> issues(Kind kind) {
        return issues.stream().filter(issue -> issue.kind() == kind).toList();
    }

    @Override
    public String toString() {
        return issues.isEmpty() ? "no issues" : issues.stream().map(Issue::toString).collect(Collectors.joining("\n"));
    }
}
