/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package it.nativeapp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.TableLocations;
import io.github.brunopacheco1.praecepta.loader.csv.CsvLoader;
import it.nativeapp.generated.Fees;
import it.nativeapp.generated.FeesInput;
import it.nativeapp.generated.Shipping;
import it.nativeapp.generated.ShippingInput;

/**
 * An application that uses Praecepta the ways a native executable would: classes the Maven plugin generated
 * from a CSV and a DMN table, a DMN file read with {@code DmnReader}, a CSV file read with the loader, and
 * the DMN files {@code praecepta-integration} finds by its default location. Every check compares with the
 * answer the DMN semantics give; the same checks run on the JVM ({@code NativeAppTest}) and, built with
 * GraalVM's native-image, as an executable that exits with 1 if any fails.
 */
public final class NativeApp {

    private final List<String> failures = new ArrayList<>();
    private int passed;

    public static void main(String[] args) {
        var failures = new NativeApp().run();
        if (!failures.isEmpty()) {
            failures.forEach(failure -> System.err.println("FAILED " + failure));
            System.exit(1);
        }
    }

    /** Runs every check, printing each, and returns the failures. */
    public List<String> run() {
        generatedFromCsv();
        generatedFromDmn();
        dmnReadAtRuntime();
        csvLoadedAtRuntime();
        integrationDefaultLocation();
        xmlErrors();
        System.out.printf("%d checks passed, %d failed%n", passed, failures.size());
        return List.copyOf(failures);
    }

    /** Fees (COLLECT SUM, an enum input), generated from rules/fees.csv. */
    private void generatedFromCsv() {
        check("Fees islands", () -> Fees.evaluate(new FeesInput(FeesInput.Zone.ISLANDS)).orElseThrow().fee(), new BigDecimal("17.5"));
        check("Fees mainland", () -> Fees.evaluate(new FeesInput(FeesInput.Zone.MAINLAND)).orElseThrow().fee(), new BigDecimal("5"));
        check("Fees report", () -> Fees.validationReport().isEmpty(), true);
    }

    /** Shipping (FIRST), generated from rules/shipping.dmn: enum, number, date, time with an offset, list. */
    private void generatedFromDmn() {
        var air = new ShippingInput(ShippingInput.Country.PT, new BigDecimal("4"), LocalDate.of(2025, 6, 1),
                OffsetTime.parse("10:30:00Z"), List.of("ES", "BR"));
        check("Shipping air carrier", () -> Shipping.evaluate(air).orElseThrow().carrier(), "air");
        // 4 * 1.5 + 2
        check("Shipping air cost", () -> Shipping.evaluate(air).orElseThrow().cost().compareTo(new BigDecimal("8")), 0);
        // 11:30Z is 12:30+01:00, so not before the cut-off
        var late = new ShippingInput(ShippingInput.Country.PT, new BigDecimal("4"), LocalDate.of(2025, 6, 1),
                OffsetTime.parse("11:30:00Z"), List.of("BR"));
        check("Shipping after the cut-off", () -> Shipping.evaluate(late).orElseThrow().carrier(), "ground");
        var noBrazil = new ShippingInput(ShippingInput.Country.PT, new BigDecimal("4"), LocalDate.of(2025, 6, 1),
                OffsetTime.parse("10:30:00Z"), List.of("ES"));
        check("Shipping list without BR", () -> Shipping.evaluate(noBrazil).orElseThrow().carrier(), "ground");
    }

    /**
     * tables/pricing.dmn, read with DmnReader (and written back with DmnWriter) and bound to maps: a date-time compared
     * across a summer-time change.
     */
    private void dmnReadAtRuntime() {
        DecisionTable table;
        try (var dmn = NativeApp.class.getResourceAsStream("/tables/pricing.dmn")) {
            table = DmnReader.read(Objects.requireNonNull(dmn, "tables/pricing.dmn"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        check("Pricing written and read back", () -> DmnReader.read(new ByteArrayInputStream(
                DmnWriter.write(table).getBytes(StandardCharsets.UTF_8))).equals(table), true);
        var pricing = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output((OutputStrategy<Map<String, String>>) PraeceptaOutput::values)
                .failOn(Severity.ERROR)
                .build();
        // 03:00 in Paris on 2025-03-30 is 01:00Z (summer time starts at 02:00 local, 01:00Z)
        check("Pricing just before summer time",
                () -> pricing.evaluate(Map.of("at", "2025-03-30T00:59:59Z", "amount", "100")).getFirst(),
                Map.of("season", "winter", "total", "110"));
        check("Pricing summer time, by zone id",
                () -> pricing.evaluate(Map.of("at", "2025-03-30T03:00:00@Europe/Paris", "amount", "100")).getFirst(),
                Map.of("season", "summer", "total", "120"));
        check("Pricing summer time, by offset",
                () -> pricing.evaluate(Map.of("at", "2025-03-30T01:00:00Z", "amount", "10.5")).getFirst(),
                Map.of("season", "summer", "total", "12.6"));
    }

    /** tables/discount.csv, read from the class path with the loader's CsvLoader. */
    private void csvLoadedAtRuntime() {
        var rules = new CsvLoader("tables/discount.csv", Set.of("country", "segment"), Set.of("grade")).getPraecepta();
        var discount = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("grade"))
                .allowedValues("country", List.of("PT", "ES", "FR"))
                .rules(rules)
                .build();
        check("Discount PT SMB", () -> discount.evaluate(Map.of("country", "PT", "segment", "SMB")), List.of("A"));
        check("Discount ES other", () -> discount.evaluate(Map.of("country", "ES", "segment", "Enterprise")), List.of("B"));
        check("Discount FR", () -> discount.evaluate(Map.of("country", "FR", "segment", "SMB")), List.of());
        check("Discount rejects undeclared values", () -> {
            try {
                discount.evaluate(Map.of("country", "BR", "segment", "SMB"));
                return "accepted";
            } catch (InvalidInputException e) {
                return "rejected";
            }
        }, "rejected");
    }

    /** rules/eligibility.dmn, found by praecepta-integration's default location and loaded with Decisions. */
    private void integrationDefaultLocation() {
        var sources = TableLocations.find(List.of(TableLocations.DEFAULT), NativeApp.class.getClassLoader());
        check("Decisions found", () -> sources.size(), 1);
        var decisions = Decisions.load(sources, Decisions.failOn("ERROR"));
        check("Decisions names", decisions::names, Set.of("Eligibility", "Risk Points"));
        check("Eligibility adult", () -> decisions.evaluate("Eligibility", Map.of("age", "30")), List.of(Map.of("group", "adult")));
        check("Eligibility gap reported", () -> decisions.get("Eligibility").report().issues().getFirst().kind().name(), "GAP");
        check("Risk Points sum", () -> decisions.evaluate("Risk Points", Map.of("score", "600")), List.of(Map.of("points", "11.5")));
    }

    /** The JDK's XML parser in DmnReader: its error messages, and a DOCTYPE refused. */
    private void xmlErrors() {
        check("Malformed DMN", () -> readError("<definitions"), true);
        check("DOCTYPE refused",
                () -> readError("<!DOCTYPE definitions [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><definitions>&x;</definitions>"), true);
    }

    /** Whether reading the text fails with a message from the XML parser. */
    private static boolean readError(String dmn) {
        try {
            DmnReader.read(new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8)));
            return false;
        } catch (InvalidDecisionTableException e) {
            System.out.println("  (" + e.getMessage() + ")");
            return e.getMessage().startsWith("Cannot read DMN model: ") && e.getMessage().length() > "Cannot read DMN model: ".length() + 10;
        }
    }

    private void check(String name, Supplier<?> actual, Object expected) {
        Object value;
        try {
            value = actual.get();
        } catch (RuntimeException | Error e) {
            failures.add(name + ": threw " + e);
            e.printStackTrace();
            return;
        }
        if (Objects.equals(value, expected)) {
            passed++;
            System.out.println("ok " + name);
        } else {
            failures.add(name + ": expected " + expected + " but got " + value);
        }
    }
}
