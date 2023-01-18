package io.com.github.brunopacheco1.praecepta.benchmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(ResourceRuntimeHints.class)
public class PraeceptaBenchmarkApplication {

	public static void main(String[] args) {
		SpringApplication.run(PraeceptaBenchmarkApplication.class, args);
	}
}
