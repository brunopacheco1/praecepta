package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import org.kie.kogito.dmn.AbstractDecisionModels;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.ApplicationScope;

@Service
@ApplicationScope
public class DecisionModels extends AbstractDecisionModels {

    static {
        init(
                null, null, readResource(Application.class.getResourceAsStream("/decision_table.dmn"), "UTF-8"));
    }

    public DecisionModels(org.kie.kogito.Application app) {
        super(app);
    }
}
