package com.nexa.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NexaApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(NexaApiApplication.class, args);
	}

}
