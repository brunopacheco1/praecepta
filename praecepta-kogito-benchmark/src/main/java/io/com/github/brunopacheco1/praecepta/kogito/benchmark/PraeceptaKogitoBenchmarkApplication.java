package io.com.github.brunopacheco1.praecepta.kogito.benchmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;


@SpringBootApplication
@ImportRuntimeHints(ResourceRuntimeHints.class)
public class PraeceptaKogitoBenchmarkApplication {

    public static void main(String[] args) {
        SpringApplication.run(PraeceptaKogitoBenchmarkApplication.class, args);
    }

}
