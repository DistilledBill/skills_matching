package com.example.resumescreening;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ResumeScreeningApplication {

	public static void main(String[] args) {
		SpringApplication.run(ResumeScreeningApplication.class, args);
	}

}
