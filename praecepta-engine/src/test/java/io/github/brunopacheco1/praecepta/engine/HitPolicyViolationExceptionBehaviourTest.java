/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;

/** Pins the messages and accessors of {@link HitPolicyViolationException}. */
class HitPolicyViolationExceptionBehaviourTest {

    @Test
    void the_message_explains_each_policy() {
        Assertions.assertThat(new HitPolicyViolationException(HitPolicy.UNIQUE, List.of(1, 2)))
                .hasMessage("UNIQUE hit policy violated: rules [1, 2] all match");
        Assertions.assertThat(new HitPolicyViolationException(HitPolicy.ANY, List.of(3, 4)))
                .hasMessage("ANY hit policy violated: matching rules [3, 4] have different outputs");
        Assertions.assertThat(new HitPolicyViolationException(HitPolicy.FIRST, List.of(5)))
                .hasMessage("FIRST hit policy violated by rules [5]");
    }

    @Test
    void the_rule_identifiers_are_copied() {
        var ids = new ArrayList<>(List.of(1, 2));
        var exception = new HitPolicyViolationException(HitPolicy.UNIQUE, ids);
        ids.add(3);
        Assertions.assertThat(exception.getRuleIds()).containsExactly(1, 2);
        Assertions.assertThat(exception.getHitPolicy()).isEqualTo(HitPolicy.UNIQUE);
    }
}
