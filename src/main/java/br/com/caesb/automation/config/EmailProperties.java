package br.com.caesb.automation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;

@Component
@ConfigurationProperties(prefix = "email")
public class EmailProperties {
    
    private Sendgrid sendgrid = new Sendgrid();
    private String from;
    private String fromName;
    private String to;
    private List<String> recipients = new ArrayList<>();
    private boolean enabled = true;

    public static class Sendgrid {
        private String apiKey;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public Sendgrid getSendgrid() {
        return sendgrid;
    }

    public void setSendgrid(Sendgrid sendgrid) {
        this.sendgrid = sendgrid;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getRecipients() {
        return recipients;
    }

    public void setRecipients(List<String> recipients) {
        this.recipients = recipients;
    }

    /**
     * Retorna todos os destinatários (combinando 'to' individual e lista 'recipients')
     */
    public List<String> getAllRecipients() {
        List<String> allRecipients = new ArrayList<>();
        
        // Adiciona o destinatário individual se configurado
        if (to != null && !to.trim().isEmpty()) {
            allRecipients.add(to.trim());
        }
        
        // Adiciona todos os destinatários da lista
        if (recipients != null) {
            for (String recipient : recipients) {
                if (recipient != null && !recipient.trim().isEmpty()) {
                    String trimmedRecipient = recipient.trim();
                    if (!allRecipients.contains(trimmedRecipient)) {
                        allRecipients.add(trimmedRecipient);
                    }
                }
            }
        }
        
        return allRecipients;
    }
}
