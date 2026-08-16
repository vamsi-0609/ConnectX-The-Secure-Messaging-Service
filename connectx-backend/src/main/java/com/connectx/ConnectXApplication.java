package com.connectx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ConnectXApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectXApplication.class, args);
    }
}
