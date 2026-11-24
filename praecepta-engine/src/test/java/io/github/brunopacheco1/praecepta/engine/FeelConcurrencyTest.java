/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;

/**
 * An engine and a graph are immutable and safe to share between threads, FEEL included: a table with FEEL cells, a
 * computed column (computed once per evaluation, in state of the evaluation's own) and output entries, and a graph whose
 * literal expressions invoke closures, give 8 threads at once what they give one thread.
 */
class FeelConcurrencyTest {

    private static final int THREADS = 8;
    private static final int INPUTS = 400;

    private static final String MODEL = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
              <inputData id="i1" name="Age"><variable name="Age" typeRef="number"/></inputData>
              <inputData id="i2" name="Limit"><variable name="Limit" typeRef="number"/></inputData>
              <decision id="b" name="Band">
                <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                <informationRequirement><requiredInput href="#i2"/></informationRequirement>
                <decisionTable hitPolicy="COLLECT">
                  <input><inputExpression typeRef="number"><text>Age * 12</text></inputExpression></input>
                  <input><inputExpression typeRef="number"><text>Limit</text></inputExpression></input>
                  <output name="band"/>
                  <rule><inputEntry><text>&gt; Limit * 12</text></inputEntry><inputEntry><text>-</text></inputEntry>
                    <outputEntry><text>"over"</text></outputEntry></rule>
                  <rule><inputEntry><text>? &gt;= 216 and ? &lt;= Limit * 12</text></inputEntry><inputEntry><text>&gt; 0</text></inputEntry>
                    <outputEntry><text>Age * 12 - Limit</text></outputEntry></rule>
                  <rule><inputEntry><text>[0..Limit]</text></inputEntry><inputEntry><text>-</text></inputEntry>
                    <outputEntry><text>{a: Age, b: [a, Limit]}</text></outputEntry></rule>
                </decisionTable>
              </decision>
              <decision id="s" name="Score">
                <informationRequirement><requiredDecision href="#b"/></informationRequirement>
                <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                <literalExpression><text>{f: function(n) if n &lt;= 0 then 0 else n + f(n - 1), g: function(x) f(x) * 2,
                  r: [if Age = null then null else g(Age), Band]}.r</text></literalExpression>
              </decision>
            </definitions>""";

    @Test
    void a_feel_table_engine_and_a_graph_give_threads_what_they_give_one() throws Exception {
        var model = DmnReader.readModel(new ByteArrayInputStream(MODEL.getBytes(StandardCharsets.UTF_8)));
        var graph = DecisionGraph.builder().model(model).logValidationReport(false).build();
        var table = ((DecisionLogic.Table) model.decisions().getFirst().logic()).table();
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder().table(table).inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values).logValidationReport(false).build();

        var inputs = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < INPUTS; i++) {
            var input = new HashMap<String, Object>();
            input.put("Age", i % 7 == 0 ? null : i % 40);
            input.put("Limit", i % 5 == 0 ? null : i % 30);
            inputs.add(input);
        }
        Function<Map<String, Object>, Object> evaluate = input -> List.of(String.valueOf(graph.evaluateWithErrors("Score", input)),
                String.valueOf(engine.evaluate(asText(input))));
        var sequential = inputs.stream().map(evaluate).toList();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            var futures = new ArrayList<Future<List<Object>>>();
            for (int t = 0; t < THREADS; t++) {
                int offset = t;
                Callable<List<Object>> task = () -> {
                    var results = new ArrayList<>(inputs.size());
                    for (int i = 0; i < inputs.size(); i++) {
                        results.add(null);
                    }
                    // each thread goes through the inputs from another place, so the threads evaluate different inputs at once
                    for (int k = 0; k < inputs.size(); k++) {
                        int i = (k + offset * 37) % inputs.size();
                        results.set(i, evaluate.apply(inputs.get(i)));
                    }
                    return results;
                };
                futures.add(pool.submit(task));
            }
            for (var future : futures) {
                assertThat(future.get()).isEqualTo(sequential);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, String> asText(Map<String, Object> input) {
        var text = new HashMap<String, String>();
        input.forEach((name, value) -> text.put(name, value == null ? null : String.valueOf(value)));
        return text;
    }
}
