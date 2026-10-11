package com.ecabin.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EcabinLedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EcabinLedgerApplication.class, args);
    }
}
