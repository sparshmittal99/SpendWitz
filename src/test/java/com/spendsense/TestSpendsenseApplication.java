package com.spendsense;

import org.springframework.boot.SpringApplication;

/** Runs the app against a throwaway pgvector container: ./mvnw spring-boot:test-run */
public class TestSpendsenseApplication {

	public static void main(String[] args) {
		SpringApplication.from(SpendsenseApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
