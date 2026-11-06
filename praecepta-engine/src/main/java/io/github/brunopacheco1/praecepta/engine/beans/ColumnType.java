package io.github.brunopacheco1.praecepta.engine.beans;

/** The type of an input column, which decides how its cells are parsed and its values compared. */
public enum ColumnType {
    /** Text, compared exactly. Cells: literals ({@code "PT"} or legacy {@code PT}), lists, {@code not(...)}. */
    STRING,
    /** Decimal numbers, compared by value ({@code 1.0} equals {@code 1}). Cells add comparisons and intervals. */
    NUMBER,
    /** Calendar dates ({@code LocalDate}). Cells use {@code date("2025-01-31")}, comparisons and intervals. */
    DATE,
}
