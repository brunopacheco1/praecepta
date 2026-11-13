/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Praecepta: DMN decision tables with strict hit policies, bitset matching and build-time
 * validation, and the decision requirement graphs around them. Needs nothing beyond the JDK; {@code java.xml}
 * reads DMN files.
 */
@SuppressWarnings("module") // the trailing digit comes from the groupId, io.github.brunopacheco1
module io.github.brunopacheco1.praecepta.engine {
    requires java.xml;

    exports io.github.brunopacheco1.praecepta.engine;
    exports io.github.brunopacheco1.praecepta.engine.beans;
    exports io.github.brunopacheco1.praecepta.engine.dmn;
    exports io.github.brunopacheco1.praecepta.engine.exceptions;
    exports io.github.brunopacheco1.praecepta.engine.feel;
}
