package com.aerocore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling 
public class AeroCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AeroCoreApplication.class, args);
    }
}
