
package br.com.caesb.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class CaesbOsAutomationApplication {
    public static void main(String[] args) {
        SpringApplication.run(CaesbOsAutomationApplication.class, args);
    }
}
