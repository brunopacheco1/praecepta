package io.github.brunopacheco1.praecepta.engine;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Static analysis of a decision table, computed when the engine is built. It is exact: every issue
 * corresponds to inputs that actually occur, and every such input region is examined.
 *
 * @param issues findings, most severe first
 */
public record ValidationReport(List<Issue> issues) {

    public ValidationReport {
        issues = List.copyOf(issues);
    }

    public enum Severity {
        WARNING,
        ERROR,
    }

    public enum Kind {
        /** No input ever selects this rule: earlier or higher-priority rules always win (FIRST, PRIORITY). */
        UNREACHABLE_RULE(Severity.WARNING),
        /** Two rules that some inputs match together (UNIQUE): evaluating those inputs raises a violation. */
        OVERLAPPING_RULES(Severity.ERROR),
        /** Two rules with different outputs that some inputs match together (ANY): a violation. */
        CONFLICTING_OUTPUTS(Severity.ERROR);

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
     */
    public record Issue(Kind kind, List<Integer> ruleIds) {

        public Issue {
            ruleIds = List.copyOf(ruleIds);
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
