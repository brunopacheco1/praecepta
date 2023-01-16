package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import java.util.Map;

import org.kie.kogito.Application;
import org.kie.kogito.dmn.rest.DMNJSONUtils;
import org.kie.kogito.dmn.rest.KogitoDMNResult;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto.BenchmarkOutput;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class BenchmarkEngine {

    private static final String NAMESPACE = "https://kiegroup.org/dmn/_E4965903-26B9-4D3C-A2C2-4131FF3D42F9";
    private static final String MODEL_NAME = "DRD";
    private static final String DECISION_RESULT = "result";

    private final ObjectMapper objectMapper;
    private final Application application;

    public BenchmarkOutput evaluate(BenchmarkInput input) {
        var decision = application.get(DecisionModels.class).getDecisionModel(NAMESPACE, MODEL_NAME);
        var variables = objectMapper.convertValue(input, new TypeReference<Map<String, Object>>() {
        });
        var decisionResult = decision.evaluateAll(DMNJSONUtils.ctx(decision, variables));
        var result = new KogitoDMNResult(NAMESPACE, MODEL_NAME, decisionResult)
                .getDecisionResultByName(DECISION_RESULT)
                .getResult();
        return objectMapper.convertValue(result, BenchmarkOutput.class);
    }
}
