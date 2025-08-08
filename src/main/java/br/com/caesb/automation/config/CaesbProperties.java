package br.com.caesb.automation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CaesbProperties {

    @Value("${caesb.username}")
    private String username;

    @Value("${caesb.password}")
    private String password;

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
