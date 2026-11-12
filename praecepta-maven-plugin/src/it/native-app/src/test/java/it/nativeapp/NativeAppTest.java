/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package it.nativeapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The native executable's checks, on the JVM: they hold in every build, so a native failure is a native one. */
class NativeAppTest {

    @Test
    void every_check_passes_on_the_jvm() {
        assertEquals(List.of(), new NativeApp().run());
    }
}
