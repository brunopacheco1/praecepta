/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * The types the harness reads from a model it does not hand the engine whole ({@link TckTypes}), how it credits a name
 * the model does not define, and how the baseline it checks against is read.
 */
class TckTypesTest {

    @TempDir
    Path directory;

    private DmnModel model(String elements) throws IOException {
        var file = directory.resolve("model.dmn");
        Files.writeString(file, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" xmlns:other="https://example.com/other"
                    id="d" name="d" namespace="n">
                %s
                </definitions>""".formatted(elements));
        return new DmnModel(file);
    }

    @Test
    void a_type_ref_names_a_built_in_type_or_an_item_definition() throws IOException {
        var model = model("""
                <itemDefinition name="tAmount"><typeRef>number</typeRef></itemDefinition>
                <itemDefinition name="tAmounts" isCollection="true"><typeRef>tAmount</typeRef></itemDefinition>
                <itemDefinition name="tPerson">
                  <itemComponent name="name"><typeRef>string</typeRef></itemComponent>
                  <itemComponent name="tags" isCollection="true"><typeRef>string</typeRef></itemComponent>
                  <itemComponent name="address"><itemComponent name="city"><typeRef>string</typeRef></itemComponent></itemComponent>
                </itemDefinition>
                <itemDefinition name="tPrefixed"><typeRef>other:tAmount</typeRef></itemDefinition>
                <itemDefinition name="tRule"><functionItem outputTypeRef="boolean"/></itemDefinition>
                <itemDefinition name="tLoop"><typeRef>tLoop</typeRef></itemDefinition>
                """);
        var number = FeelType.of(ColumnType.NUMBER);
        var string = FeelType.of(ColumnType.STRING);
        assertThat(TckTypes.of(model, "date")).isEqualTo(FeelType.of(ColumnType.DATE));
        assertThat(TckTypes.of(model, "tAmount")).isEqualTo(number);
        assertThat(TckTypes.of(model, "tAmounts")).isEqualTo(FeelType.listOf(ColumnType.NUMBER));
        assertThat(TckTypes.of(model, "tPerson")).isEqualTo(new FeelType.Context(Map.of("name", string, "tags", new FeelType.ListOf(string),
                "address", new FeelType.Context(Map.of("city", string)))));
        assertThat(TckTypes.of(model, "tPrefixed")).as("a prefix names the model's own type here").isEqualTo(number);
        // a function type, a type the model does not define, and one that never ends are of any type
        assertThat(TckTypes.of(model, "tRule")).isEqualTo(FeelType.ANY);
        assertThat(TckTypes.of(model, "tUnknown")).isEqualTo(FeelType.ANY);
        assertThat(TckTypes.of(model, "tLoop")).isEqualTo(FeelType.ANY);
    }

    @Test
    void a_name_the_model_does_not_define_is_an_imported_models_where_the_model_imports_one() throws IOException {
        var importing = model("""
                <import namespace="https://example.com/other" name="other" importType="https://www.omg.org/spec/DMN/20230324/MODEL/"/>
                """);
        assertThat(LiteralExpressionEvaluator.unknownName("other.Greeting + \"!\"", importing)).containsExactly(Feature.IMPORTS);
        var alone = model("<inputData id=\"a\" name=\"A\"/>");
        assertThat(LiteralExpressionEvaluator.unknownName("B + 1", alone)).containsExactly(LiteralExpressionEvaluator.OTHER_NAMES);
    }

    @Test
    void a_baseline_that_lists_a_node_twice_or_tags_one_otherwise_fails_unless_it_is_being_regenerated() throws IOException {
        var twice = directory.resolve("twice.txt");
        Files.writeString(twice, "# comment\na\nb\tgraph\na\tgraph\n");
        assertThatThrownBy(() -> Baseline.read(twice, false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("lists 'a' twice");
        assertThat(Baseline.read(twice, true)).containsEntry("a", "graph");

        var mistyped = directory.resolve("mistyped.txt");
        Files.writeString(mistyped, "a\tgraph \nb\n");
        assertThatThrownBy(() -> Baseline.read(mistyped, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tags 'a' with 'graph '");
        assertThat(Baseline.read(mistyped, true)).containsOnlyKeys(List.of("a", "b"));
    }
}
