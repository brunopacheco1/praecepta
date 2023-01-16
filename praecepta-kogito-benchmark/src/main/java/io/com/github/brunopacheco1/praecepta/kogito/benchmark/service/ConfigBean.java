package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import java.util.Optional;

import org.kie.kogito.KogitoGAV;
import org.kie.kogito.config.StaticConfigBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class ConfigBean extends StaticConfigBean {

    @Value("${kogito.service.url:#{null}}")
    Optional<String> kogitoService;

    @Value("${kogito.messaging.as-cloudevents:#{true}}")
    boolean useCloudEvents;

    @Value("${kogito.jackson.fail-on-empty-bean:#{false}}")
    boolean failOnEmptyBean;

    @PostConstruct
    protected void init() {
        setServiceUrl(kogitoService.orElse(""));
        setCloudEvents(useCloudEvents);
        setFailOnEmptyBean(failOnEmptyBean);
        setGav(new KogitoGAV("io.github.brunopacheco1", "praecepta-kogito-benchmark", "0.0-SNAPSHOT"));
    }
}
