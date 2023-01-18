package io.com.github.brunopacheco1.praecepta.camunda.benchmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(ResourceRuntimeHints.class)
public class PraeceptaCamundaBenchmarkApplication {

	public static void main(String[] args) {
		SpringApplication.run(PraeceptaCamundaBenchmarkApplication.class, args);
	}

}
