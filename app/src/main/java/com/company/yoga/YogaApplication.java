package com.company.yoga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.transaction.annotation.EnableTransactionManagement(order = 0)
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class YogaApplication {

    public static void main(String[] args) {
        SpringApplication.run(YogaApplication.class, args);
    }
}
