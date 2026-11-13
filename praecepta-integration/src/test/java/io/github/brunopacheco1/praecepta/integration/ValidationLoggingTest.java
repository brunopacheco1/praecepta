/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;

/**
 * Each validation issue is logged once: {@link DecisionsLoader} logs it through the framework's logger, so
 * the engines it builds do not log their reports too (#95). Used on its own, {@link Decisions#load} keeps
 * the engine's logging unless told otherwise.
 */
class ValidationLoggingTest {

    /** A FIRST table whose second rule can never be selected: one WARNING. */
    private static final String UNREACHABLE = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="grade" name="Grade"><decisionTable hitPolicy="FIRST">
                <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                <output name="grade" typeRef="string"/>
                <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"A"</text></outputEntry></rule>
                <rule><inputEntry><text>&gt; 5</text></inputEntry><outputEntry><text>"B"</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    private static final TableSource SOURCE = TableSource.of("grade.dmn", UNREACHABLE.getBytes(StandardCharsets.UTF_8));

    private final Logger engineLogger = Logger.getLogger(PraeceptaEngine.class.getName());
    private final List<LogRecord> engineRecords = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            engineRecords.add(logRecord);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };
    private Level level;

    @BeforeEach
    void capture() {
        // the build's test-logging.properties keeps the engine's warnings quiet: let them through to the handler
        level = engineLogger.getLevel();
        engineLogger.setLevel(Level.ALL);
        engineLogger.addHandler(handler);
    }

    @AfterEach
    void release() {
        engineLogger.removeHandler(handler);
        engineLogger.setLevel(level);
    }

    @Test
    void the_loader_logs_each_issue_once_and_the_engines_log_nothing() {
        var warnings = new ArrayList<String>();
        var decisions = new DecisionsLoader("praecepta", null, "ERROR")
                .declared(List.of(SOURCE), List.of())
                .load(location -> List.of(), new DecisionsLoader.Log() {
                    @Override
                    public void info(String message) {
                        // not what this test reads
                    }

                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                });

        Assertions.assertThat(decisions.get("Grade").report().issues()).hasSize(1);
        Assertions.assertThat(warnings).containsExactly("Grade: " + decisions.get("Grade").report().issues().getFirst());
        Assertions.assertThat(engineRecords).isEmpty();
    }

    @Test
    void loading_on_its_own_keeps_the_engines_logging() {
        var decisions = Decisions.load(List.of(SOURCE), Severity.ERROR);

        Assertions.assertThat(engineRecords).singleElement()
                .satisfies(logged -> Assertions.assertThat(logged.getParameters()).contains(decisions.get("Grade").report()));
    }

    /**
     * Loading on its own, a file whose decision tables load alone because its model uses what a later release plans is
     * logged once at INFO by {@link Decisions}' own logger; without logging, it is not.
     */
    @Test
    void loading_on_its_own_logs_each_file_whose_tables_load_alone() {
        var later = TableSource.of("later.dmn", UNREACHABLE.replace("<decision ", "<businessKnowledgeModel id=\"bkm\" name=\"Rate\"/><decision ")
                .replace("name=\"Grade\"", "name=\"Later Grade\"").getBytes(StandardCharsets.UTF_8));
        var logger = Logger.getLogger(Decisions.class.getName());
        var records = new ArrayList<LogRecord>();
        var capture = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        var previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
        try {
            Decisions.load(List.of(later, SOURCE), Severity.ERROR);
            Assertions.assertThat(records).singleElement().satisfies(logged -> {
                Assertions.assertThat(logged.getLevel()).isEqualTo(Level.INFO);
                Assertions.assertThat(logged.getMessage()).startsWith("later.dmn: its decision tables are loaded alone, ")
                        .contains("0.10 (#118)", "business knowledge model 'Rate'");
            });
            records.clear();
            Decisions.load(List.of(later), Severity.ERROR, false);
            Assertions.assertThat(records).isEmpty();
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previous);
        }
    }

    @Test
    void loading_without_logging_keeps_the_same_reports() {
        var quiet = Decisions.load(List.of(SOURCE), Severity.ERROR, false);

        Assertions.assertThat(engineRecords).isEmpty();
        Assertions.assertThat(quiet.get("Grade").report()).isEqualTo(Decisions.load(List.of(SOURCE), Severity.ERROR).get("Grade").report());
    }

    @Test
    void a_decision_built_without_logging_logs_nothing() {
        var table = DmnReader.readAll(new ByteArrayInputStream(UNREACHABLE.getBytes(StandardCharsets.UTF_8))).getFirst();

        Assertions.assertThat(Decision.of("grade.dmn", table, false).report().issues()).hasSize(1);
        Assertions.assertThat(engineRecords).isEmpty();
        Decision.of("grade.dmn", table);
        Assertions.assertThat(engineRecords).hasSize(1);
    }
}
