/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;

/**
 * Dates, times and date-times are read in XML Schema's lexical forms (DMN 1.5, 10.3.4.1, date string, time string and
 * date time string). Their offsets lie in -14:00..+14:00, as Table 66 bounds the time offset property: an offset beyond
 * them (#123) is not a time, where 0.6 read {@code 13:20:00+19:00} as {@code 13:20:00}, dropping the offset Java does
 * not take, and {@code 13:20:00+15:00} at that offset. A year has an optional {@code -} and no {@code +} (#124), where
 * 0.6 read {@code +99999-12-01} as Java writes it, and four digits or more, so {@code 99999-12-31} is a date, which 0.6
 * did not read. Each place Praecepta reads one rejects other text: a cell, a value list, an output entry and a default
 * output when a table is read from DMN or its engine built, as it rejects any invalid literal; and a map-based input or
 * a graph's input data given as text when it is evaluated, as any text that is not of the column's type
 * ({@code InvalidInputException}). The DMN TCK expects FEEL's null from {@code time(from)}, {@code date(from)} and
 * {@code date and time(from)} of such a string ({@code 1116-feel-time-function}, {@code ErrorCase_067} and
 * {@code _068}; {@code 1115-feel-date-function}, {@code ErrorCase_041}; {@code 1117-feel-date-and-time-function},
 * {@code ErrorCase_068}, {@code _078} and {@code _079}), and {@code date and time("99999-12-31T11:22:33")} to be a
 * date and time ({@code 1117-feel-date-and-time-function}, {@code 011}).
 */
class TemporalLexicalFormsTest {

    /**
     * A table of one input and one output of a type, holding literals in the input's value list, its entry, the rule's
     * output entry and the output's default, in that order.
     */
    private static final String TABLE = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
              <decision id="Slot" name="Slot"><decisionTable hitPolicy="FIRST">
                <input><inputExpression typeRef="%1$s"><text>at</text></inputExpression>
                  <inputValues><text>%2$s</text></inputValues></input>
                <output name="until" typeRef="%1$s"><defaultOutputEntry><text>%5$s</text></defaultOutputEntry></output>
                <rule><inputEntry><text>%3$s</text></inputEntry><outputEntry><text>%4$s</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    /** A decision requirements graph whose decision reads an input data of a type. */
    private static final String GRAPH = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="g" name="g" namespace="n">
              <inputData id="at" name="At"><variable name="At" typeRef="%s"/></inputData>
              <decision id="echo" name="Echo"><variable name="Echo" typeRef="%1$s"/>
                <informationRequirement><requiredInput href="#at"/></informationRequirement>
                <literalExpression><text>At</text></literalExpression></decision>
            </definitions>""";

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = ';', value = {
            "time;          time(\"08:00:00+14:00\");                time(\"08:00:00-14:00\");                time(\"08:00:00+14:01\")",
            "time;          time(\"08:00:00+14:00\");                time(\"08:00:00-14:00\");                time(\"08:00:00+19:00\")",
            "time;          time(\"08:00:00+14:00\");                time(\"08:00:00-14:00\");                @\"08:00:00-19:00\"",
            "dateTime;      date and time(\"2025-01-31T08:00:00+14:00\"); date and time(\"2025-01-31T08:00:00-14:00\"); date and time(\"2025-01-31T08:00:00+15:00\")",
            "dateTime;      date and time(\"2025-01-31T08:00:00+14:00\"); date and time(\"2025-01-31T08:00:00-14:00\"); @\"2025-01-31T08:00:00-19:00\"",
            "date;          date(\"99999-12-31\");                    date(\"-0044-03-15\");                    date(\"+2012-12-02\")",
            "date;          date(\"99999-12-31\");                    date(\"-0044-03-15\");                    @\"+99999-12-31\"",
            "date;          date(\"99999-12-31\");                    date(\"-0044-03-15\");                    date(\"01211-12-31\")",
            "dateTime;      date and time(\"99999-12-31T11:22:33\");  date and time(\"-99999-12-31T11:22:33\"); date and time(\"+99999-12-01T11:22:33\")",
            "dateTime;      date and time(\"99999-12-31T11:22:33\");  date and time(\"-99999-12-31T11:22:33\"); @\"+2012-12-24T23:59:00@Europe/Paris\"",
    })
    void a_literal_beyond_the_lexical_form_is_rejected_wherever_a_table_holds_it(String type, String valid, String other, String invalid) {
        var values = valid + ", " + other;
        Assertions.assertThat(engine(TABLE.formatted(type, values, valid, valid, other)).evaluate(Map.of("at", text(valid))))
                .containsExactly(Map.of("until", text(valid)));
        // the input's value list, its entry, the rule's output entry, the output's default
        for (var dmn : new String[] {TABLE.formatted(type, valid + ", " + invalid, valid, valid, other),
                TABLE.formatted(type, values, invalid, valid, other), TABLE.formatted(type, values, valid, invalid, other),
                TABLE.formatted(type, values, valid, valid, invalid)}) {
            Assertions.assertThatThrownBy(() -> engine(dmn)).as(dmn).hasMessageContaining(text(invalid));
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "time,     08:00:00+14:00,            08:00:00+14:01",
            "time,     08:00:00-14:00,            08:00:00-19:00",
            "dateTime, 2025-01-31T08:00:00+14:00, 2025-01-31T08:00:00+15:00",
            "dateTime, 2025-01-31T08:00:00-14:00, 2025-01-31T08:00:00-19:00",
            "date,     99999-12-31,               +99999-12-31",
            "date,     2012-12-02,                +2012-12-02",
            "dateTime, 99999-12-31T11:22:33,      +99999-12-31T11:22:33",
            "dateTime, 2012-12-24T23:59:00Z,      +2012-12-24T23:59:00Z",
    })
    void text_beyond_the_lexical_form_is_an_invalid_input_as_any_text_not_of_the_type(String type, String valid, String invalid) {
        var literal = "%s(\"%s\")".formatted(columnType(type).feelName(), valid);
        var engine = engine(TABLE.formatted(type, literal, literal, literal, literal));
        Assertions.assertThat(engine.evaluate(Map.of("at", valid))).containsExactly(Map.of("until", valid));
        var beyond = Map.of("at", invalid);
        Assertions.assertThatThrownBy(() -> engine.evaluate(beyond)).isInstanceOf(InvalidInputException.class).hasMessageContaining(invalid);
        Assertions.assertThatThrownBy(() -> engine.evaluate(Map.of("at", "noon"))).isInstanceOf(InvalidInputException.class);

        var graph = DecisionGraph.read(stream(GRAPH.formatted(type)));
        Assertions.assertThat(graph.evaluate("Echo", Map.of("At", valid))).isEqualTo(FeelLiterals.value(valid, columnType(type)));
        Assertions.assertThatThrownBy(() -> graph.evaluate("Echo", Map.of("At", invalid)))
                .isInstanceOf(InvalidInputException.class).hasMessageContaining(invalid);
    }

    private static ColumnType columnType(String typeRef) {
        return switch (typeRef) {
            case "date" -> ColumnType.DATE;
            case "time" -> ColumnType.TIME;
            default -> ColumnType.DATE_TIME;
        };
    }

    /** The text of a literal: its string. */
    private static String text(String literal) {
        return literal.substring(literal.indexOf('"') + 1, literal.lastIndexOf('"'));
    }

    private static PraeceptaEngine<Map<String, String>, Map<String, String>> engine(String dmn) {
        return PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(DmnReader.read(stream(dmn)))
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .build();
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
