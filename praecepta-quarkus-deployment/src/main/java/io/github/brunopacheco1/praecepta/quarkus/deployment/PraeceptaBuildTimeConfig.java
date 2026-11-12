/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The Praecepta extension's settings, all fixed when Quarkus builds the application. They mean what
 * {@code praecepta.*} means in the Spring Boot and Micronaut integrations (docs/INTEGRATIONS.md), under
 * Quarkus' prefix for extensions.
 */
@ConfigMapping(prefix = "quarkus.praecepta")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface PraeceptaBuildTimeConfig {

    /**
     * The DMN files to load, as location patterns. {@code classpath:} and {@code classpath*:} both search
     * every class path root of the application (its own resources and every dependency); {@code file:}
     * searches the file system. {@code *} matches within a path segment, {@code **} across segments.
     * Defaults to {@code classpath*:rules/**}{@code /*.dmn}, where finding nothing is fine; a pattern set
     * here that finds nothing fails the build.
     */
    Optional<List<String>> locations();

    /**
     * The validation severity that fails the build: {@code ERROR}, {@code WARNING} or {@code NONE}, in any
     * case. Issues below it are logged as warnings. Tables that cannot be read or built fail the build
     * whatever this says.
     */
    @WithDefault("ERROR")
    String failOn();

    /**
     * Excel and CSV tables to compile at build time, by a key of your choice, with the settings of the
     * Maven plugin's {@code <table>} in kebab-case. The decision is named after the file (without its
     * extension), as the build plugins name it. A DMN file may be given here too, to pick one of its
     * decisions with {@code decision}.
     */
    Map<String, TableConfig> tables();

    /** One table to compile, as the Maven plugin's {@code <table>} declares it. */
    interface TableConfig {

        /**
         * The {@code .xlsx}, {@code .csv} or {@code .dmn} file: an absolute path, or one relative to the
         * project's directory (where its {@code pom.xml} or {@code build.gradle} is), such as
         * {@code src/main/rules/discount.csv}. Keep spreadsheets out of {@code src/main/resources}, so they
         * are not packaged.
         */
        Optional<String> source();

        /** DMN only: the decision to read, when the model has several decision tables. */
        Optional<String> decision();

        /** Excel and CSV only, and required there: the table's hit policy. */
        Optional<HitPolicy> hitPolicy();

        /** Excel and CSV only: SUM, MIN, MAX or COUNT, for a COLLECT table with one NUMBER output. */
        Optional<AggregationOperator> aggregation();

        /** Excel and CSV only: the input columns, in order. */
        Optional<List<ColumnConfig>> inputs();

        /** Excel and CSV only: the output columns, in order. */
        Optional<List<ColumnConfig>> outputs();
    }

    /** A column of an Excel or CSV table. */
    interface ColumnConfig {

        /** The column's header in the sheet. */
        String name();

        /**
         * The column's type: STRING, NUMBER, DATE, BOOLEAN, TIME, DATE_TIME, DAY_TIME_DURATION or
         * YEAR_MONTH_DURATION.
         */
        @WithDefault("STRING")
        ColumnType type();

        /**
         * Inputs only: the DMN input value list, as unary tests ({@code "PT","ES"} or {@code [0..120]}).
         * Values outside it are rejected.
         */
        Optional<String> allowedValues();

        /** Outputs only: the output value list, highest priority first (PRIORITY and OUTPUT_ORDER). */
        Optional<List<String>> priority();

        /** Inputs only: whether the column holds lists of its type, such as a customer's nationalities. */
        @WithDefault("false")
        boolean list();
    }
}
