package io.com.github.brunopacheco1.praecepta.camunda.benchmark.service;

import io.com.github.brunopacheco1.praecepta.camunda.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco1.praecepta.camunda.benchmark.dto.BenchmarkOutput;
import io.com.github.brunopacheco1.praecepta.camunda.benchmark.dto.InputOutputConst;
import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnDecisionTableResult;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class BenchmarkEngine {

    private final DmnEngine dmnEngine;
    private final DmnDecision decision;

    public BenchmarkEngine(
            @Value("${decision-table.path}") String dmnPath
    ) {
        dmnEngine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
        decision = dmnEngine.parseDecision("decision_table", this.getClass().getResourceAsStream(dmnPath));
    }

    public BenchmarkOutput evaluate(BenchmarkInput input) {
        DmnDecisionTableResult result = dmnEngine.evaluateDecisionTable(decision, from(input));
        return from(result);
    }

    private Map<String, Object> from(BenchmarkInput benchmarkInput) {
        var values = new HashMap<String, Object>();
        values.put(InputOutputConst.INPUT_A, benchmarkInput.getInputA());
        values.put(InputOutputConst.INPUT_B, benchmarkInput.getInputB());
        values.put(InputOutputConst.INPUT_C, benchmarkInput.getInputC());
        values.put(InputOutputConst.INPUT_D, benchmarkInput.getInputD());
        values.put(InputOutputConst.INPUT_E, benchmarkInput.getInputE());
        values.put(InputOutputConst.INPUT_F, benchmarkInput.getInputF());
        values.put(InputOutputConst.INPUT_G, benchmarkInput.getInputG());
        values.put(InputOutputConst.INPUT_H, benchmarkInput.getInputH());
        values.put(InputOutputConst.INPUT_I, benchmarkInput.getInputI());
        values.put(InputOutputConst.INPUT_J, benchmarkInput.getInputJ());
        values.put(InputOutputConst.INPUT_K, benchmarkInput.getInputK());
        values.put(InputOutputConst.INPUT_L, benchmarkInput.getInputL());
        values.put(InputOutputConst.INPUT_M, benchmarkInput.getInputB());
        return values;
    }

    private BenchmarkOutput from(DmnDecisionTableResult result) {
        var decisionResult = result.getFirstResult();
        return BenchmarkOutput.builder()
                .outputA(decisionResult.getEntry(InputOutputConst.OUTPUT_A))
                .outputB(decisionResult.getEntry(InputOutputConst.OUTPUT_B))
                .ruleId((decisionResult.getEntry(InputOutputConst.RULE_ID)))
                .build();
    }
}
