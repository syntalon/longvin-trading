package com.longvin.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LongvinTradingBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(LongvinTradingBackendApplication.class, args);
	}

}
