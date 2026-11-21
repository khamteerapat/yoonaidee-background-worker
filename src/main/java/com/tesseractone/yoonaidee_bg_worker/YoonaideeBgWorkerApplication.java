package com.tesseractone.yoonaidee_bg_worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class YoonaideeBgWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.run(YoonaideeBgWorkerApplication.class, args);
	}

}
