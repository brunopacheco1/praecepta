/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * What a native executable needs of the extension: the validated DMN written into the application as resources,
 * and those resources registered for the native image, which reads them while it is built. A build step added to
 * the build chain checks the build items the extension produces; it fails the build if they are wrong.
 */
class NativeResourcesTest {

    /** Where the checking step leaves what it found, for the test: a system property, which both class loaders see. */
    private static final String FOUND = NativeResourcesTest.class.getName() + ".found";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root
                    .addAsResource("tables/discount.dmn", "rules/discount.dmn")
                    .addAsResource("tables/shipping.dmn", "rules/shipping/shipping.dmn"))
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                @Override
                public void execute(BuildContext context) {
                    check(context.consumeMulti(GeneratedResourceBuildItem.class), context.consumeMulti(NativeImageResourceBuildItem.class));
                    context.produce(new FeatureBuildItem("praecepta-native-resources-test"));
                }
            }).consumes(GeneratedResourceBuildItem.class).consumes(NativeImageResourceBuildItem.class).produces(FeatureBuildItem.class).build());

    private static void check(List<GeneratedResourceBuildItem> generated, List<NativeImageResourceBuildItem> registered) {
        var tables = new TreeMap<String, String>();
        generated.stream()
                .filter(resource -> resource.getName().startsWith(PraeceptaProcessor.RESOURCES))
                .forEach(resource -> tables.put(resource.getName(), new String(resource.getData(), StandardCharsets.UTF_8)));
        var names = registered.stream().flatMap(item -> item.getResources().stream())
                .filter(name -> name.startsWith(PraeceptaProcessor.RESOURCES)).sorted().toList();
        if (!names.equals(List.copyOf(tables.keySet()))) {
            throw new IllegalStateException("Registered for the native image: %s; written: %s".formatted(names, tables.keySet()));
        }
        tables.forEach((name, dmn) -> {
            if (!dmn.contains("<decisionTable")) {
                throw new IllegalStateException(name + " holds no decision table");
            }
        });
        System.setProperty(FOUND, String.join(",", names));
    }

    @Test
    void every_validated_table_is_a_resource_registered_for_the_native_image() {
        Assertions.assertThat(System.getProperty(FOUND))
                .isEqualTo("META-INF/praecepta/tables/0.dmn,META-INF/praecepta/tables/1.dmn");
    }
}
