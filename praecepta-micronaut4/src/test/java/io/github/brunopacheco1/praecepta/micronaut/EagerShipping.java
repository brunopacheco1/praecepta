/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;

/**
 * An application bean created with the context, like the module's {@code Decisions}: it can inject an
 * engine only because the module's bean is created first.
 */
@Context
@Requires(property = "praecepta-test.eager")
class EagerShipping {

    private final String lightCarrier;

    EagerShipping(@Named("Carrier") PraeceptaEngine<Map<String, String>, Map<String, String>> carrier) {
        this.lightCarrier = carrier.evaluate(Map.of("weight", "1")).getFirst().get("Carrier");
    }

    String lightCarrier() {
        return lightCarrier;
    }
}
