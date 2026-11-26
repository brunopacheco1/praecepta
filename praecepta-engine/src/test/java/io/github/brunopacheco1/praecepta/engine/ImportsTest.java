/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/** Models that import others (DMN 1.5, 6.3.3), read with the models they import and evaluated as one graph (#119). */
class ImportsTest {

    private static InputStream resource(String name) {
        return ImportsTest.class.getResourceAsStream("/dmn/graph/imports/" + name);
    }

    /** Finds an imported model by its location, beside the importing one. */
    private static final DmnReader.ImportResolver BESIDE = (namespace, location) -> resource(location);

    private static DecisionModel main() {
        return DmnReader.readModel(resource("main.dmn"), BESIDE);
    }

    private static final Map<String, Object> GUEST = Map.of("Guest", Map.of("name", "Ada", "age", 36));

    @Test
    void a_model_is_read_with_the_models_it_imports_each_once() {
        var model = main();

        assertThat(model.namespace()).isEqualTo("https://example.com/main");
        assertThat(model.imports()).extracting(DecisionModel.Import::name).containsExactly("people", "labels");
        var people = model.imports().getFirst().model();
        var labels = model.imports().get(1).model();
        assertThat(labels.imports().getFirst().model()).as("People, imported twice, is read once").isSameAs(people);
        assertThat(model.inputData().getFirst().type().component("age")).as("an imported type").isEqualTo(FeelType.of(ColumnType.NUMBER));
        var welcome = model.decision("Welcome").orElseThrow();
        assertThat(welcome.requiredDecisions()).as("an href into an imported model, by the name its import gives it")
                .containsExactly("labels.Label");
        assertThat(welcome.requiredKnowledge()).containsExactly("people.Greet");
        assertThat(labels.decision("Label").orElseThrow().requiredDecisions()).as("an import without a name").containsExactly("Adult");
    }

    @Test
    void the_decisions_of_a_model_and_of_those_it_imports_are_evaluated_as_one_graph() {
        var graph = DecisionGraph.builder().model(main()).logValidationReport(false).build();
        var person = graph.nameOf("https://example.com/people", "Person");
        var inputs = Map.of("Guest", GUEST.get("Guest"), person, Map.of("name", "Bob", "age", 15));

        assertThat(graph.validationReport().issues()).isEmpty();
        assertThat(person).as("the name the first path of imports gives it").isEqualTo("people.Person");
        assertThat(graph.evaluate("Welcome", inputs)).isEqualTo("Hello Ada; host: Bob (minor)");
        assertThat(graph.requiredInputData("Welcome")).containsExactlyInAnyOrder("Guest", "people.Person");
        assertThat(graph.evaluate("people.Adult", inputs)).isEqualTo(false);
        assertThat(graph.model().imports()).as("the model, as it was read").hasSize(2);
    }

    @Test
    void a_model_whose_imports_are_not_given_or_found_or_import_it_in_turn_is_rejected() {
        assertThatThrownBy(() -> DmnReader.readModel(resource("main.dmn"))).isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("imports 'people'");
        assertThatThrownBy(() -> DmnReader.readModel(resource("main.dmn"), (namespace, location) -> null))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("imports the model 'https://example.com/people' at 'people.dmn', which cannot be found");
        var selfImporting = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="s" name="S" namespace="https://example.com/s">
                  <import name="me" namespace="https://example.com/s" importType="https://www.omg.org/spec/DMN/20230324/MODEL/"/>
                </definitions>""";
        assertThatThrownBy(() -> DmnReader.readModel(stream(selfImporting), (namespace, location) -> stream(selfImporting)))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("imports itself");
        var schema = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="x" name="X" namespace="https://example.com/x">
                  <import name="types" namespace="https://example.com/types" importType="http://www.w3.org/2001/XMLSchema"/>
                </definitions>""";
        assertThatThrownBy(() -> DmnReader.readModel(stream(schema), (namespace, location) -> null))
                .isInstanceOf(UnsupportedFeatureException.class).hasMessageContaining("which Praecepta does not read: it imports DMN models");
    }

    @Test
    void a_model_and_those_it_imports_written_as_dmn_read_back_as_the_same_models() {
        var model = main();
        var written = new HashMap<String, String>();
        written.put(model.namespace(), DmnWriter.write(model));
        for (var imported : model.imports()) {
            written.put(imported.model().namespace(), DmnWriter.write(imported.model()));
        }

        var read = DmnReader.readModel(stream(written.get(model.namespace())), (namespace, location) -> stream(written.get(namespace)));

        assertThat(written.get(model.namespace())).contains("<import name=\"people\" namespace=\"https://example.com/people\"")
                .contains("href=\"https://example.com/labels#decision_0\"");
        assertThat(read).isEqualTo(model);
        var graph = DecisionGraph.builder().model(read).logValidationReport(false).build();
        var inputs = Map.of("Guest", GUEST.get("Guest"), "people.Person", Map.of("name", "Bob", "age", 15));
        assertThat(graph.evaluate("Welcome", inputs)).isEqualTo("Hello Ada; host: Bob (minor)");
    }

    @Test
    void an_href_may_name_the_models_own_namespace() {
        // the DMN TCK's 0091-local-hrefs
        var model = DmnReader.readModel(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="l" name="L" namespace="https://example.com/l">
                  <inputData id="x" name="X"><variable name="X" typeRef="number"/></inputData>
                  <decision id="d" name="D">
                    <informationRequirement><requiredInput href="https://example.com/l#x"/></informationRequirement>
                    <literalExpression><text>X * 2</text></literalExpression>
                  </decision>
                </definitions>"""));

        assertThat(model.decision("D").orElseThrow().requiredInputs()).containsExactly("X");
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
