/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;

/**
 * Converts an input to its column values as text, for {@code PraeceptaEngine.Builder#inputs}. Simpler
 * than binding each column to a getter, but slower: it runs on every evaluation, and each value is
 * then parsed as its column's type.
 */
@FunctionalInterface
public interface InputStrategy<I> {

    /** The input's column values by column name; a missing or blank value is a missing input. */
    PraeceptaInput transform(I input);
}
