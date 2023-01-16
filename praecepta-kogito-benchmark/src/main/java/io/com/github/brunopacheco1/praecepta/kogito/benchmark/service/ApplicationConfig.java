package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import java.util.Collection;
import java.util.Set;

import org.kie.kogito.Addons;
import org.kie.kogito.KogitoConfig;
import org.kie.kogito.StaticConfig;
import org.springframework.stereotype.Service;

@Service
public class ApplicationConfig extends StaticConfig {

    public ApplicationConfig(Collection<KogitoConfig> configs) {
        super(new Addons(Set.of()), configs);
    }
}
