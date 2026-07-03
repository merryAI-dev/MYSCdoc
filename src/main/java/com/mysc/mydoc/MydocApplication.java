package com.mysc.mydoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class MydocApplication {

    public static void main(String[] args) {
        SpringApplication.run(MydocApplication.class, args);
    }
}
