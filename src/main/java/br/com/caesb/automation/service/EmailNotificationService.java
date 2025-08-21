package br.com.caesb.automation.service;

import br.com.caesb.automation.config.EmailProperties;
import br.com.caesb.automation.dto.BaixaResultado;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class EmailNotificationService {

    private static final Logger logger = LogManager.getLogger(EmailNotificationService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @Autowired
    private EmailProperties emailProperties;

    /**
     * Envia notificação de sucesso quando a baixa é finalizada com êxito
     */
    public void enviarNotificacaoSucesso(String numeroOs, LocalDateTime dataInicio, LocalDateTime dataFim) {
        if (!emailProperties.isEnabled()) {
            logger.info("Email notifications are disabled. Skipping success notification for OS {}", numeroOs);
            return;
        }

        try {
            String assunto = "✅ Baixa Finalizada com Sucesso - OS " + numeroOs;
            String conteudo = criarConteudoSucesso(numeroOs, dataInicio, dataFim);
            
            enviarEmail(assunto, conteudo);
            logger.info("Success notification sent for OS {}", numeroOs);
            
        } catch (Exception e) {
            logger.error("Failed to send success notification for OS {}: {}", numeroOs, e.getMessage(), e);
        }
    }

    /**
     * Envia notificação de erro quando ocorre falha durante a baixa
     */
    public void enviarNotificacaoErro(String numeroOs, String mensagemErro, LocalDateTime dataInicio) {
        if (!emailProperties.isEnabled()) {
            logger.info("Email notifications are disabled. Skipping error notification for OS {}", numeroOs);
            return;
        }

        try {
            String assunto = "❌ Erro na Baixa - Bot Parado - OS " + numeroOs;
            String conteudo = criarConteudoErro(numeroOs, mensagemErro, dataInicio);
            
            enviarEmail(assunto, conteudo);
            logger.info("Error notification sent for OS {}", numeroOs);
            
        } catch (Exception e) {
            logger.error("Failed to send error notification for OS {}: {}", numeroOs, e.getMessage(), e);
        }
    }

    /**
     * Envia notificação com múltiplos resultados de baixa
     */
    public void enviarNotificacaoResumo(List<BaixaResultado> resultados, LocalDateTime dataInicio, LocalDateTime dataFim) {
        if (!emailProperties.isEnabled()) {
            logger.info("Email notifications are disabled. Skipping summary notification");
            return;
        }

        try {
            long sucessos = resultados.stream().mapToLong(r -> r.sucesso() ? 1 : 0).sum();
            long erros = resultados.size() - sucessos;
            
            String assunto = String.format("📊 Resumo da Execução - %d Sucessos, %d Erros", sucessos, erros);
            String conteudo = criarConteudoResumo(resultados, dataInicio, dataFim);
            
            enviarEmail(assunto, conteudo);
            logger.info("Summary notification sent for {} results", resultados.size());
            
        } catch (Exception e) {
            logger.error("Failed to send summary notification: {}", e.getMessage(), e);
        }
    }

    private void enviarEmail(String assunto, String conteudo) throws IOException {
        List<String> allRecipients = emailProperties.getAllRecipients();
        
        if (allRecipients.isEmpty()) {
            logger.warn("No recipients configured for email notifications");
            return;
        }

        Email from = new Email(emailProperties.getFrom(), emailProperties.getFromName());
        Content content = new Content("text/html", conteudo);
        
        // Usar o primeiro destinatário como principal
        Email primaryTo = new Email(allRecipients.get(0));
        Mail mail = new Mail(from, assunto, primaryTo, content);
        
        // Adicionar destinatários adicionais como CC se houver mais de um
        if (allRecipients.size() > 1) {
            for (int i = 1; i < allRecipients.size(); i++) {
                mail.personalization.get(0).addCc(new Email(allRecipients.get(i)));
            }
        }

        SendGrid sg = new SendGrid(emailProperties.getSendgrid().getApiKey());
        Request request = new Request();
        
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);
            
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                logger.info("Email sent successfully to {} recipients. Status: {}", 
                    allRecipients.size(), response.getStatusCode());
                logger.debug("Recipients: {}", allRecipients);
            } else {
                logger.error("Failed to send email to {} recipients. Status: {}, Body: {}", 
                    allRecipients.size(), response.getStatusCode(), response.getBody());
            }
        } catch (IOException ex) {
            logger.error("IOException while sending email to {} recipients: {}", 
                allRecipients.size(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private String criarConteudoSucesso(String numeroOs, LocalDateTime dataInicio, LocalDateTime dataFim) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html><head><meta charset='UTF-8'></head><body>");
        html.append("<div style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;'>");
        html.append("<h2 style='color: #28a745;'>✅ Baixa Finalizada com Sucesso</h2>");
        html.append("<div style='background-color: #d4edda; border: 1px solid #c3e6cb; padding: 15px; border-radius: 5px; margin: 15px 0;'>");
        html.append("<h3 style='margin-top: 0; color: #155724;'>Detalhes da Execução</h3>");
        html.append("<p><strong>Número da OS:</strong> ").append(numeroOs).append("</p>");
        html.append("<p><strong>Início:</strong> ").append(dataInicio.format(FORMATTER)).append("</p>");
        html.append("<p><strong>Fim:</strong> ").append(dataFim.format(FORMATTER)).append("</p>");
        html.append("<p><strong>Status:</strong> <span style='color: #28a745; font-weight: bold;'>SUCESSO</span></p>");
        html.append("</div>");
        html.append("<p style='color: #6c757d; font-size: 12px; margin-top: 20px;'>");
        html.append("Este email foi enviado automaticamente pelo sistema de automação CAESB.<br>");
        html.append("Data/Hora: ").append(LocalDateTime.now().format(FORMATTER));
        html.append("</p>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String criarConteudoErro(String numeroOs, String mensagemErro, LocalDateTime dataInicio) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html><head><meta charset='UTF-8'></head><body>");
        html.append("<div style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;'>");
        html.append("<h2 style='color: #dc3545;'>❌ Erro na Execução da Baixa</h2>");
        html.append("<div style='background-color: #f8d7da; border: 1px solid #f5c6cb; padding: 15px; border-radius: 5px; margin: 15px 0;'>");
        html.append("<h3 style='margin-top: 0; color: #721c24;'>⚠️ Bot Parado Devido a Erro</h3>");
        html.append("<p><strong>Número da OS:</strong> ").append(numeroOs != null ? numeroOs : "N/A").append("</p>");
        html.append("<p><strong>Início da Execução:</strong> ").append(dataInicio.format(FORMATTER)).append("</p>");
        html.append("<p><strong>Hora do Erro:</strong> ").append(LocalDateTime.now().format(FORMATTER)).append("</p>");
        html.append("<p><strong>Status:</strong> <span style='color: #dc3545; font-weight: bold;'>ERRO</span></p>");
        html.append("</div>");
        html.append("<div style='background-color: #fff3cd; border: 1px solid #ffeaa7; padding: 15px; border-radius: 5px; margin: 15px 0;'>");
        html.append("<h4 style='margin-top: 0; color: #856404;'>Detalhes do Erro:</h4>");
        html.append("<p style='font-family: monospace; background-color: #f8f9fa; padding: 10px; border-radius: 3px;'>");
        html.append(mensagemErro != null ? mensagemErro : "Erro desconhecido");
        html.append("</p>");
        html.append("</div>");
        html.append("<div style='background-color: #cce5ff; border: 1px solid #b3d9ff; padding: 15px; border-radius: 5px; margin: 15px 0;'>");
        html.append("<h4 style='margin-top: 0; color: #004085;'>🔧 Ação Necessária:</h4>");
        html.append("<p>O bot foi interrompido e requer intervenção manual. Verifique os logs para mais detalhes e reinicie o processo quando o problema for resolvido.</p>");
        html.append("</div>");
        html.append("<p style='color: #6c757d; font-size: 12px; margin-top: 20px;'>");
        html.append("Este email foi enviado automaticamente pelo sistema de automação CAESB.<br>");
        html.append("Data/Hora: ").append(LocalDateTime.now().format(FORMATTER));
        html.append("</p>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String criarConteudoResumo(List<BaixaResultado> resultados, LocalDateTime dataInicio, LocalDateTime dataFim) {
        long sucessos = resultados.stream().mapToLong(r -> r.sucesso() ? 1 : 0).sum();
        long erros = resultados.size() - sucessos;
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html><head><meta charset='UTF-8'></head><body>");
        html.append("<div style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;'>");
        html.append("<h2 style='color: #007bff;'>📊 Resumo da Execução</h2>");
        
        // Estatísticas gerais
        html.append("<div style='background-color: #e7f3ff; border: 1px solid #b3d9ff; padding: 15px; border-radius: 5px; margin: 15px 0;'>");
        html.append("<h3 style='margin-top: 0; color: #004085;'>Estatísticas Gerais</h3>");
        html.append("<p><strong>Total de OSs processadas:</strong> ").append(resultados.size()).append("</p>");
        html.append("<p><strong>Sucessos:</strong> <span style='color: #28a745;'>").append(sucessos).append("</span></p>");
        html.append("<p><strong>Erros:</strong> <span style='color: #dc3545;'>").append(erros).append("</span></p>");
        html.append("<p><strong>Início:</strong> ").append(dataInicio.format(FORMATTER)).append("</p>");
        html.append("<p><strong>Fim:</strong> ").append(dataFim.format(FORMATTER)).append("</p>");
        html.append("</div>");
        
        // Detalhes dos resultados
        if (!resultados.isEmpty()) {
            html.append("<div style='background-color: #f8f9fa; border: 1px solid #dee2e6; padding: 15px; border-radius: 5px; margin: 15px 0;'>");
            html.append("<h3 style='margin-top: 0; color: #495057;'>Detalhes dos Resultados</h3>");
            html.append("<table style='width: 100%; border-collapse: collapse;'>");
            html.append("<tr style='background-color: #e9ecef;'>");
            html.append("<th style='border: 1px solid #dee2e6; padding: 8px; text-align: left;'>OS</th>");
            html.append("<th style='border: 1px solid #dee2e6; padding: 8px; text-align: left;'>Status</th>");
            html.append("<th style='border: 1px solid #dee2e6; padding: 8px; text-align: left;'>Mensagem</th>");
            html.append("</tr>");
            
            for (BaixaResultado resultado : resultados) {
                html.append("<tr>");
                html.append("<td style='border: 1px solid #dee2e6; padding: 8px;'>").append(resultado.os()).append("</td>");
                if (resultado.sucesso()) {
                    html.append("<td style='border: 1px solid #dee2e6; padding: 8px; color: #28a745; font-weight: bold;'>✅ SUCESSO</td>");
                } else {
                    html.append("<td style='border: 1px solid #dee2e6; padding: 8px; color: #dc3545; font-weight: bold;'>❌ ERRO</td>");
                }
                html.append("<td style='border: 1px solid #dee2e6; padding: 8px; font-size: 12px;'>");
                html.append(String.join(", ", resultado.mensagens()));
                html.append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");
            html.append("</div>");
        }
        
        html.append("<p style='color: #6c757d; font-size: 12px; margin-top: 20px;'>");
        html.append("Este email foi enviado automaticamente pelo sistema de automação CAESB.<br>");
        html.append("Data/Hora: ").append(LocalDateTime.now().format(FORMATTER));
        html.append("</p>");
        html.append("</div></body></html>");
        return html.toString();
    }
}
