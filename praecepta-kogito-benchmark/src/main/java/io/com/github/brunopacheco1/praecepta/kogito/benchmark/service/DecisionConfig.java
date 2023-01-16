package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import java.util.List;
import org.kie.dmn.api.core.event.DMNRuntimeEventListener;
import org.kie.kogito.decision.DecisionEventListenerConfig;
import org.kie.kogito.dmn.config.AbstractDecisionConfig;
import org.springframework.stereotype.Service;

@Service
class DecisionConfig extends AbstractDecisionConfig {

    public DecisionConfig(List<DecisionEventListenerConfig> decisionEventListenerConfigs, List<DMNRuntimeEventListener> dmnRuntimeEventListeners) {
        super(decisionEventListenerConfigs, dmnRuntimeEventListeners);
    }
}
