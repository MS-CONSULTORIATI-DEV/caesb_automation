package br.com.caesb.automation.controller;

import br.com.caesb.automation.config.CaesbSession;
import br.com.caesb.automation.dto.BaixaResultado;
import br.com.caesb.automation.service.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@EnableAsync        // habilita @Async
@RestController
@RequestMapping("/api/os")
public class CaesbController {

    private static final Logger log = LogManager.getLogger(CaesbController.class);

    /* -----------------------------------------------------------
       DEPENDÊNCIAS
       ----------------------------------------------------------- */
    private final CaesbLoginService  loginService;
    private final CaesbOsService     osService;
    private final CaesbBaixaService  baixaService;
    private final EmailNotificationService emailNotificationService;

    private final BaixaControladorExecucao controle;

    /* -----------------------------------------------------------
       CONFIGURAÇÕES
       ----------------------------------------------------------- */

    @Value("${caesb.execucao.intervalo-vazio-ms:30000}")
    private long intervaloSemOsMs;

    /* Pool para executar a tarefa em background */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public CaesbController(CaesbLoginService loginService,
                           CaesbOsService osService,
                           CaesbBaixaService baixaService,
                           EmailNotificationService emailNotificationService,
                           BaixaControladorExecucao controle) {
        this.loginService  = loginService;
        this.osService     = osService;
        this.baixaService  = baixaService;
        this.emailNotificationService = emailNotificationService;
        this.controle      = controle;
    }

    /* -----------------------------------------------------------
       1. LISTAR OS (sincrono)
       ----------------------------------------------------------- */
    @GetMapping
    public ResponseEntity<List<String>> listarOs() throws Exception {
        CaesbSession session = loginService.login();
        List<String> numeros = osService.listarOs(session);

        try {
            emailNotificationService.enviarNotificacaoSucesso("TESTE", LocalDateTime.now(), LocalDateTime.now());
        } catch (Exception emailError) {
            log.warn("[{}] Failed to send summary notification: {}", numeros, emailError.getMessage());
        }


        return ResponseEntity.ok(numeros);
    }

    /* -----------------------------------------------------------
       2. INICIAR PROCESSO ASSÍNCRONO DE BAIXA
       ----------------------------------------------------------- */
    @PostMapping("/baixar")
    public ResponseEntity<String> iniciarBaixa() {
        if (controle.isExecutando()) {
            return ResponseEntity.status(409).body("Já existe processo em execução.");
        }

        String jobId = UUID.randomUUID().toString();
        controle.iniciar();

        executor.submit(() -> executarBaixa(jobId));

        return ResponseEntity.accepted()
                .body("Processo iniciado. jobId=" + jobId);
    }

    /* -----------------------------------------------------------
       3. PARAR PROCESSO
       ----------------------------------------------------------- */
    @PostMapping("/baixar/parar")
    public ResponseEntity<String> pararBaixa() {
        log.info("Requisição para parar o processo recebida.");
        if (!controle.isExecutando()) {
            log.warn("Tentativa de parar processo que não está em execução.");
            return ResponseEntity.status(409).body("Nenhum processo em execução.");
        }
        controle.parar();
        log.info("Parada do processo solicitada com sucesso.");
        return ResponseEntity.ok("Parada solicitada.");
    }

    /* -----------------------------------------------------------
       4. STATUS
       ----------------------------------------------------------- */
    @GetMapping("/baixar/status")
    public ResponseEntity<String> status() {
        if (controle.isExecutando()) {
            String status = "Em execução desde " + controle.getInicio();
            String osAtual = controle.getOsAtual();
            if (osAtual != null) {
                status += " | OS atual: " + osAtual;
            }
            return ResponseEntity.ok(status);
        }
        return ResponseEntity.ok("Parado");

    }

    /* -----------------------------------------------------------
       ROTINA PRINCIPAL (thread em background)
       ----------------------------------------------------------- */
    private void executarBaixa(String jobId) {
        log.info("[{}] processo de baixa iniciado", jobId);
        LocalDateTime inicioProcesso = LocalDateTime.now();

        List<BaixaResultado> resultados = new CopyOnWriteArrayList<>();

        try {
            CaesbSession session = loginService.login();

            while (controle.isExecutar()) {

                List<String> osPendentes = osService.listarOs(session);

                if (osPendentes.isEmpty()) {
                    log.info("[{}] nenhuma OS encontrada; aguardando {} ms", jobId, intervaloSemOsMs);
                    Thread.sleep(intervaloSemOsMs);
                    continue;
                }

                int limite = osPendentes.size();
                for (int i = 0; i < limite && controle.isExecutar(); i++) {
                    String os = osPendentes.get(i);
                    controle.setOsAtual(os);
                    try {
                        BaixaResultado r = baixaService.baixarOs(session, os);
//                        BaixaResultado r = new BaixaResultado("teste", true, null);
                        resultados.add(r);
                        log.info("[{}] OS {} ⇒ {}", jobId, os,
                                r.sucesso() ? "OK" : "ERRO: " + r.mensagens());
                    } catch (Exception e) {
                        log.error("[{}] Falha inesperada na OS {}", jobId, os, e);
                        resultados.add(new BaixaResultado(os, false,
                                List.of("Falha inesperada: " + e.getMessage())));
                        
                        // Enviar notificação de erro crítico que parou o bot
                        try {
                            emailNotificationService.enviarNotificacaoErro(os, 
                                "Falha inesperada que parou o bot: " + e.getMessage(), inicioProcesso);
                        } catch (Exception emailError) {
                            log.warn("[{}] Failed to send critical error notification for OS {}: {}", 
                                jobId, os, emailError.getMessage());
                        }
                        
                        controle.parar();
                    }
                }

            }

        } catch (Exception geral) {
            log.error("[{}] erro geral no processo", jobId, geral);
            
            // Enviar notificação de erro geral
            try {
                emailNotificationService.enviarNotificacaoErro(null, 
                    "Erro geral no processo: " + geral.getMessage(), inicioProcesso);
            } catch (Exception emailError) {
                log.warn("[{}] Failed to send general error notification: {}", jobId, emailError.getMessage());
            }
            
        } finally {
            controle.parar();       // garante reset
            LocalDateTime fimProcesso = LocalDateTime.now();
            log.info("[{}] processo encerrado – {} resultados", jobId, resultados.size());
            
            // Enviar resumo se houver resultados
            if (!resultados.isEmpty()) {
                try {
                    emailNotificationService.enviarNotificacaoResumo(resultados, inicioProcesso, fimProcesso);
                } catch (Exception emailError) {
                    log.warn("[{}] Failed to send summary notification: {}", jobId, emailError.getMessage());
                }
            }
        }
    }
}
