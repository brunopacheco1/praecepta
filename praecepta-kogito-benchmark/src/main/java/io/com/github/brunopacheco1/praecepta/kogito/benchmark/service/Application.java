package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import java.util.Collection;

import org.kie.kogito.Config;
import org.kie.kogito.KogitoEngine;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.ApplicationScope;

@Service
@ApplicationScope
public class Application extends org.kie.kogito.StaticApplication {

    public Application(Config config, Collection<KogitoEngine> engines) {
        super(config, engines);
    }
}
