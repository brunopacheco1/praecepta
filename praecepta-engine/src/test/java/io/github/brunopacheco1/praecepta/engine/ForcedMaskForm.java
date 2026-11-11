/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

/**
 * The form an engine stores its rule masks in ({@link Masks.Form}), for tests outside this package:
 * the property tests build every random table with one of them, so that both forms, and tables mixing
 * them, are checked against the reference.
 */
public enum ForcedMaskForm {
    /** Every mask dense. */
    DENSE(Masks.Form.DENSE),
    /** Chosen by density, as the engine does by default. */
    AUTO(Masks.Form.AUTO),
    /** Every mask sparse that the encoding allows. */
    SPARSE(Masks.Form.SPARSE),
    /** Some masks sparse and some dense, whatever their density. */
    MIXED(Masks.Form.MIXED);

    private final Masks.Form form;

    ForcedMaskForm(Masks.Form form) {
        this.form = form;
    }

    /** Makes the builder store its masks in this form. */
    public <I, O> PraeceptaEngine.Builder<I, O> applyTo(PraeceptaEngine.Builder<I, O> builder) {
        return builder.maskForm(form);
    }
}
