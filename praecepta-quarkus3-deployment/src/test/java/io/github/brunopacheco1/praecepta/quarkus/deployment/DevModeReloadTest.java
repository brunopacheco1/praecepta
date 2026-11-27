/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;

import org.assertj.core.api.Assertions;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.test.common.http.TestHTTPResource;

/**
 * In dev mode, changing a table (a DMN resource, or a declared spreadsheet anywhere on disk) restarts the
 * application with the new table, and a broken table is reported until fixed.
 */
class DevModeReloadTest {

    /** A declared CSV table outside the application's resources, which the test changes. */
    private static final Path PRICING = copyOf(Path.of("src/test/resources/tables/discount.csv"));

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot(app -> app
                    .addClass(DiscountRoute.class)
                    .addAsResource("tables/discount.dmn", "rules/discount.dmn")
                    .addAsResource(new StringAsset(String.join("\n",
                            "quarkus.http.port=0",
                            "quarkus.praecepta.tables.pricing.source=" + PRICING.toString().replace('\\', '/'),
                            "quarkus.praecepta.tables.pricing.hit-policy=FIRST",
                            "quarkus.praecepta.tables.pricing.inputs[0].name=country",
                            "quarkus.praecepta.tables.pricing.inputs[1].name=age",
                            "quarkus.praecepta.tables.pricing.inputs[1].type=NUMBER",
                            "quarkus.praecepta.tables.pricing.inputs[2].name=since",
                            "quarkus.praecepta.tables.pricing.inputs[2].type=DATE",
                            "quarkus.praecepta.tables.pricing.outputs[0].name=discount",
                            "quarkus.praecepta.tables.pricing.outputs[0].type=NUMBER",
                            "quarkus.praecepta.tables.pricing.outputs[1].name=tier",
                            "")), "application.properties"));

    @TestHTTPResource
    URL root;

    @Test
    void a_changed_table_is_reloaded() throws Exception {
        Assertions.assertThat(get("discount")).isEqualTo("[0.15, 0.05]");

        TEST.modifyResourceFile("rules/discount.dmn", dmn -> dmn.replace("<text>0.15</text>", "<text>0.2</text>"));
        Assertions.assertThat(get("discount")).isEqualTo("[0.2, 0.05]");

        // a table that breaks the build shows the error, and the application comes back once it is fixed
        TEST.modifyResourceFile("rules/discount.dmn", dmn -> dmn.replace("hitPolicy=\"RULE ORDER\"", "hitPolicy=\"UNIQUE\""));
        Assertions.assertThat(get("discount")).contains("decision 'Discount' has validation issues at ERROR or above");
        TEST.modifyResourceFile("rules/discount.dmn", dmn -> dmn.replace("hitPolicy=\"UNIQUE\"", "hitPolicy=\"RULE ORDER\""));
        Assertions.assertThat(get("discount")).isEqualTo("[0.2, 0.05]");
    }

    @Test
    void an_added_table_is_loaded() throws Exception {
        TEST.addResourceFile("rules/shipping.dmn", Files.readString(Path.of("src/test/resources/tables/shipping.dmn")));
        Assertions.assertThat(get("decisions")).isEqualTo("[Carrier, Discount, Fee, discount]");
    }

    @Test
    void a_changed_spreadsheet_is_compiled_again() throws Exception {
        Assertions.assertThat(get("decisions/discount?country=PT&age=30&since=2019-01-01")).isEqualTo("[0.15]");

        Files.writeString(PRICING, Files.readString(PRICING).replace(";0.15;", ";0.3;"));
        Files.setLastModifiedTime(PRICING, FileTime.from(Files.getLastModifiedTime(PRICING).toInstant().plusSeconds(1)));
        Assertions.assertThat(get("decisions/discount?country=PT&age=30&since=2019-01-01")).isEqualTo("[0.3]");
    }

    @AfterAll
    static void delete_the_copy() throws IOException {
        Files.deleteIfExists(PRICING);
        Files.deleteIfExists(PRICING.getParent());
    }

    private static Path copyOf(Path table) {
        try {
            var directory = Files.createTempDirectory("praecepta-dev-mode");
            return Files.copy(table, directory.resolve(table.getFileName()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String get(String path) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(root.toString()).resolve("/" + path)).timeout(Duration.ofMinutes(1)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }
}
