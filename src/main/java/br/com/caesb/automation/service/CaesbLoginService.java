package br.com.caesb.automation.service;

import br.com.caesb.automation.config.CaesbProperties;
import br.com.caesb.automation.config.CaesbSession;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Service
public class CaesbLoginService {

    private static final Logger logger = LogManager.getLogger(CaesbLoginService.class);

    private final CaesbProperties properties;

    @Autowired
    public CaesbLoginService(CaesbProperties properties) {
        this.properties = properties;
    }

    public CaesbSession login() {
        return loginWithRetry(3);
    }
    
    /**
     * Faz login com retry logic para lidar com ERR_CONNECTION_RESET
     */
    private CaesbSession loginWithRetry(int maxRetries) {
        logger.info("🔐 Iniciando processo de login...");
        
        Exception lastException = null;
        
        for (int tentativa = 1; tentativa <= maxRetries; tentativa++) {
            try {
                logger.info("Tentativa {} de {} de fazer login", tentativa, maxRetries);
                return executarLogin();
            } catch (com.microsoft.playwright.PlaywrightException pe) {
                lastException = pe;
                
                if (pe.getMessage().contains("ERR_CONNECTION_RESET")) {
                    logger.warn("⚠️ ERR_CONNECTION_RESET durante login (tentativa {}/{})", tentativa, maxRetries);
                    logger.warn("Servidor pode estar bloqueando requisições. Aguardando antes de tentar novamente...");
                    
                    if (tentativa < maxRetries) {
                        try {
                            int delay = tentativa * 3000; // 3s, 6s, 9s
                            logger.info("💤 Aguardando {}ms antes da próxima tentativa...", delay);
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Login interrompido", ie);
                        }
                    }
                } else {
                    // Outro erro do Playwright, não faz retry
                    throw pe;
                }
            } catch (Exception e) {
                lastException = e;
                logger.error("❌ Erro inesperado durante login (tentativa {}/{}): {}", 
                        tentativa, maxRetries, e.getMessage());
                
                if (tentativa < maxRetries) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Login interrompido", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
        
        logger.error("❌ Falha no login após {} tentativas", maxRetries);
        throw new RuntimeException("Falha no login após " + maxRetries + " tentativas", lastException);
    }
    
    /**
     * Executa o login propriamente dito
     */
    private CaesbSession executarLogin() {
        try (Playwright playwright = Playwright.create()) {
            // Argumentos otimizados para Linux/Debian (mesmos do CaesbBaixaService)
            java.util.List<String> chromeArgs = new java.util.ArrayList<>();
            chromeArgs.add("--disable-dev-shm-usage");
            chromeArgs.add("--no-sandbox");
            chromeArgs.add("--disable-setuid-sandbox");
            chromeArgs.add("--disable-gpu");
            chromeArgs.add("--disable-software-rasterizer");
            chromeArgs.add("--disable-extensions");
            chromeArgs.add("--disable-background-networking");
            chromeArgs.add("--disable-default-apps");
            chromeArgs.add("--disable-sync");
            chromeArgs.add("--metrics-recording-only");
            chromeArgs.add("--mute-audio");
            chromeArgs.add("--no-first-run");
            chromeArgs.add("--safebrowsing-disable-auto-update");
            chromeArgs.add("--disable-blink-features=AutomationControlled");
            
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(chromeArgs)
                    .setTimeout(60000));
            logger.info("✓ Browser iniciado");

            com.microsoft.playwright.BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setExtraHTTPHeaders(java.util.Map.of(
                        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        "Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Accept-Encoding", "gzip, deflate, br",
                        "DNT", "1",
                        "Connection", "keep-alive",
                        "Upgrade-Insecure-Requests", "1"
                    ))
            );
            
            context.setDefaultNavigationTimeout(60000);
            context.setDefaultTimeout(45000);
            
            Page page = context.newPage();
            
            logger.info("Navegando para página de login...");
            page.navigate("https://sistemas.caesb.df.gov.br/seguranca/app/", 
                new Page.NavigateOptions().setTimeout(60000));
            page.waitForLoadState(LoadState.LOAD);
            logger.info("✓ Página de login carregada");

            // Aguardar um pouco para garantir que a página está estável
            Thread.sleep(1000);

            logger.info("Preenchendo credenciais...");
            page.locator("#j_username").fill(properties.getUsername());
            page.locator("#j_password").fill(properties.getPassword());
            
            Thread.sleep(500); // Pequena pausa entre preenchimento e clique
            
            page.locator("#btEntrar").click();
            logger.info("✓ Formulário de login enviado");

            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(2000); // Aguardar processamento
            logger.info("✓ Login processado");

            logger.info("Navegando para página de controle de OS...");
            page.navigate("https://sistemas.caesb.df.gov.br/gcom/app/atendimento/os/controleOs/controle",
                new Page.NavigateOptions().setTimeout(60000));
            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(1000);

            if (!page.url().contains("/controleOs/controle")) {
                logger.error("❌ Falha ao navegar para página de controle. URL atual: {}", page.url());
                throw new IllegalStateException("Falha ao navegar para a página de controle de OS. Login provavelmente falhou.");
            }
            logger.info("✓ Navegação para página de controle bem-sucedida");

            String viewState = page.locator("input[name='javax.faces.ViewState']").first().getAttribute("value");
            logger.info("✓ ViewState obtido");

            String url = page.url();
            String execution = url.replaceAll(".*execution=([^&]+).*", "$1");
            logger.info("✓ Execution parameter obtido");

            logger.info("Obtendo cookies...");
            java.util.List<com.microsoft.playwright.options.Cookie> cookies = page.context().cookies();
            java.util.Map<String, String> cookieMap = new java.util.HashMap<>();
            for (com.microsoft.playwright.options.Cookie c : cookies) {
                cookieMap.put(c.name, c.value);
            }
            logger.info("✓ {} cookies obtidos", cookieMap.size());

            CaesbSession session = new CaesbSession();
            session.setCookies(cookieMap);
            session.setViewState(viewState);
            session.setExecution(execution);
            
            logger.info("✅ Login concluído com sucesso!");
            return session;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Login interrompido", ie);
        } catch (Exception e) {
            logger.error("❌ Erro durante execução do login: {}", e.getMessage(), e);
            throw e;
        }
    }
}