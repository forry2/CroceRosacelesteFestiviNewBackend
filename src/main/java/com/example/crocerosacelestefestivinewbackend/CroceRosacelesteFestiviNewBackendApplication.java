package com.example.crocerosacelestefestivinewbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class CroceRosacelesteFestiviNewBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CroceRosacelesteFestiviNewBackendApplication.class, args);
	}

    @RestController
    @RequestMapping("/api")
    static class PingController {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }

}
