package io.com.github.brunopacheco1.praecepta.benchmark.service;

import io.com.github.brunopacheco1.praecepta.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco1.praecepta.benchmark.dto.BenchmarkOutput;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
public class BenchmarkEngine extends PraeceptaEngine<BenchmarkInput, BenchmarkOutput> {

    public BenchmarkEngine(BenchmarkExcelLoader loader) {
        super(HitPolicy.UNIQUE, BenchmarkEngine::from, BenchmarkEngine::from);
        loadDecisionTable(loader);
    }

    private void loadDecisionTable(BenchmarkExcelLoader loader) {
        this.register(loader.getPraecepta());
    }

    private static PraeceptaInput from(BenchmarkInput benchmarkInput) {
        var values = new HashMap<String, String>();
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
        return new PraeceptaInput(values);
    }

    private static BenchmarkOutput from(PraeceptaOutput praeceptaOutput) {
        return BenchmarkOutput.builder()
                .outputA(praeceptaOutput.values().get(InputOutputConst.OUTPUT_A))
                .outputB(praeceptaOutput.values().get(InputOutputConst.OUTPUT_B))
                .ruleId((praeceptaOutput.values().get(InputOutputConst.RULE_ID)))
                .build();
    }
}
