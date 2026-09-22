package com.relyon.economizaai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EconomizaaiApplication {

	public static void main(String[] args) {
		SpringApplication.run(EconomizaaiApplication.class, args);
	}

}
