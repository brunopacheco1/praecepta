/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.builder.item.SimpleBuildItem;

/**
 * The application's decision tables, found and validated at build time: what the runtime rebuilds its
 * engines from. Other extensions may consume it, for instance to show the reports in a development UI.
 */
public final class PraeceptaTablesBuildItem extends SimpleBuildItem {

    /**
     * One validated DMN document.
     *
     * @param location where it came from: a class path resource's URL, a file's path, or the Excel or CSV
     *                 file it was compiled from
     * @param dmn      the DMN, as read or as compiled
     */
    public record Table(String location, byte[] dmn) {

        public Table {
            Objects.requireNonNull(location, "location");
            dmn = dmn.clone();
        }

        @Override
        public byte[] dmn() {
            return dmn.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Table(var otherLocation, var otherDmn) && location.equals(otherLocation)
                    && Arrays.equals(dmn, otherDmn);
        }

        @Override
        public int hashCode() {
            return 31 * location.hashCode() + Arrays.hashCode(dmn);
        }

        @Override
        public String toString() {
            return "Table[" + location + "]";
        }
    }

    private final List<Table> tables;
    private final Decisions decisions;

    public PraeceptaTablesBuildItem(List<Table> tables, Decisions decisions) {
        this.tables = List.copyOf(tables);
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    /** The validated DMN documents, ordered by location. */
    public List<Table> tables() {
        return tables;
    }

    /** The decisions as the build validated them, with their reports. */
    public Decisions decisions() {
        return decisions;
    }
}
