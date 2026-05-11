package com.bitesharing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BiteSharingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BiteSharingApplication.class, args);
    }
}

