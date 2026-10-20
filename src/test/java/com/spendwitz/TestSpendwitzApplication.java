package com.spendwitz;

import org.springframework.boot.SpringApplication;

/** Runs the app against a throwaway pgvector container: ./mvnw spring-boot:test-run */
public class TestSpendwitzApplication {

	public static void main(String[] args) {
		SpringApplication.from(SpendwitzApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
