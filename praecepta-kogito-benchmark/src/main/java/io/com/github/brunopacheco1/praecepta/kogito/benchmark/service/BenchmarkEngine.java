package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto.BenchmarkOutput;
import io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto.InputOutputConst;
import org.kie.api.KieServices;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieRuntimeFactory;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.internal.builder.InternalKieBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkEngine {

    private static final String GROUP_ID = "io.github.brunopacheco1";
    private static final String ARTIFACT_ID = "praecepta-dmn";
    private static final String VERSION = "0.0";

    private static final String RUNTIME_DEFINITIONS = "https://kiegroup.org/dmn/_E4965903-26B9-4D3C-A2C2-4131FF3D42F9";
    private static final String RUNTIME_MODEL_NAME = "DRD";

    private final DMNRuntime dmnRuntime;

    public BenchmarkEngine(
            @Value("${decision-table.path}") String dmnPath
    ) {
        dmnRuntime = getDmnRuntime(dmnPath);
    }

    private DMNRuntime getDmnRuntime(String dmnPath) {
        var kieServices = KieServices.get();

        var releaseId = kieServices.newReleaseId(GROUP_ID, ARTIFACT_ID, VERSION);

        var kfs = kieServices.newKieFileSystem().generateAndWritePomXML(releaseId);
        kfs.write(kieServices.getResources().newClassPathResource(dmnPath));

        var kieBuilder = kieServices.newKieBuilder(kfs);
        ((InternalKieBuilder) kieBuilder).buildAll(o -> true);
        Results results = kieBuilder.getResults();

        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(results.getMessages(Message.Level.ERROR).toString());
        }

        var kieContainer = kieServices.newKieContainer(releaseId);
        return KieRuntimeFactory.of(kieContainer.getKieBase()).get(DMNRuntime.class);
    }

    public BenchmarkOutput evaluate(BenchmarkInput input) {
        var dmnContext = from(input);
        var dmnModel = dmnRuntime.getModel(RUNTIME_DEFINITIONS, RUNTIME_MODEL_NAME);
        var dmnResult = dmnRuntime.evaluateAll(dmnModel, dmnContext);
        return from(dmnResult);
    }

    private DMNContext from(BenchmarkInput benchmarkInput) {
        DMNContext dmnContext = dmnRuntime.newContext();
        dmnContext.set(InputOutputConst.INPUT_A, benchmarkInput.getInputA());
        dmnContext.set(InputOutputConst.INPUT_B, benchmarkInput.getInputB());
        dmnContext.set(InputOutputConst.INPUT_C, benchmarkInput.getInputC());
        dmnContext.set(InputOutputConst.INPUT_D, benchmarkInput.getInputD());
        dmnContext.set(InputOutputConst.INPUT_E, benchmarkInput.getInputE());
        dmnContext.set(InputOutputConst.INPUT_F, benchmarkInput.getInputF());
        dmnContext.set(InputOutputConst.INPUT_G, benchmarkInput.getInputG());
        dmnContext.set(InputOutputConst.INPUT_H, benchmarkInput.getInputH());
        dmnContext.set(InputOutputConst.INPUT_I, benchmarkInput.getInputI());
        dmnContext.set(InputOutputConst.INPUT_J, benchmarkInput.getInputJ());
        dmnContext.set(InputOutputConst.INPUT_K, benchmarkInput.getInputK());
        dmnContext.set(InputOutputConst.INPUT_L, benchmarkInput.getInputL());
        dmnContext.set(InputOutputConst.INPUT_M, benchmarkInput.getInputB());
        return dmnContext;
    }

    private BenchmarkOutput from(DMNResult result) {
        DMNContext context = result.getContext();
        return BenchmarkOutput.builder()
                .build();
    }
}
