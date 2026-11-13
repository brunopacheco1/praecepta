/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

/**
 * A DMN 1.5 feature a result node needs and Praecepta does not have, named so that the release that brings it
 * can count what it unlocks: a FEEL construct ({@code if then else}), a built-in function by name ({@code abs}),
 * business knowledge models, invocations, boxed contexts, decision services, imports, structured inputs...
 *
 * @param area the layer of the DMN 1.5 plan the feature belongs to
 * @param name the feature in a few words, stable from run to run, as RESULTS.md groups by it
 */
record Feature(Area area, String name) {

    /**
     * The layers of the plan to a full DMN 1.5 engine (tracking issue #120), one per release, in release order.
     * The first is what Praecepta has: single decision tables, and the arithmetic of output entries.
     */
    enum Area {
        DECISION_TABLES("Single decision tables and arithmetic", "0.6", 0),
        DECISION_REQUIREMENT_GRAPHS("Decision requirement graphs", "0.7", 14),
        FEEL("FEEL language", "0.8", 116),
        BUILT_IN_FUNCTIONS("Built-in functions", "0.9", 117),
        BOXED_EXPRESSIONS("Boxed expressions, business knowledge models and structured values", "0.10", 118),
        DECISION_SERVICES_AND_IMPORTS("Decision services, imports and the rest", "0.11", 119);

        private final String title;
        private final String release;
        private final int issue;

        Area(String title, String release, int issue) {
            this.title = title;
            this.release = release;
            this.issue = issue;
        }

        String title() {
            return title;
        }

        /** The release planned for it ({@code 0.8}). */
        String release() {
            return release;
        }

        /** Its issue in brunopacheco1/praecepta, or 0 for what Praecepta has. */
        int issue() {
            return issue;
        }

        /** The later of two areas: what a node needing both waits for. */
        Area max(Area other) {
            return compareTo(other) >= 0 ? this : other;
        }
    }

    // ---- the features, by area ------------------------------------------------------------------

    static Feature decisionTable(String name) {
        return new Feature(Area.DECISION_TABLES, name);
    }

    static Feature feel(String name) {
        return new Feature(Area.FEEL, name);
    }

    /** A built-in function of DMN 1.5, 10.3.4, by its FEEL name: {@code builtIn("string length")}. */
    static Feature builtIn(String function) {
        return new Feature(Area.BUILT_IN_FUNCTIONS, function + "()");
    }

    static Feature boxed(String name) {
        return new Feature(Area.BOXED_EXPRESSIONS, name);
    }

    static Feature rest(String name) {
        return new Feature(Area.DECISION_SERVICES_AND_IMPORTS, name);
    }

    static final Feature REQUIRED_DECISIONS = new Feature(Area.DECISION_REQUIREMENT_GRAPHS,
            "inputs computed by other decisions (decision requirement graphs, #14)");
    static final Feature KNOWLEDGE_MODELS = boxed("business knowledge models");
    static final Feature INVOCATIONS = boxed("invocations");
    static final Feature FUNCTION_DEFINITIONS = boxed("function definitions and lambdas");
    static final Feature STRUCTURED_INPUTS = boxed("structured inputs and outputs (item definitions with components)");
    static final Feature DECISION_SERVICES = rest("decision services");
    static final Feature IMPORTS = rest("imports");
    static final Feature EXTERNAL_FUNCTIONS = rest("external functions (Java, PMML)");
    static final Feature NO_LOGIC = rest("decisions without decision logic");

    /** The feature as RESULTS.md names it, with the release that brings it: {@code abs() (0.9)}. */
    String label() {
        return name + " (" + area.release() + ")";
    }
}
