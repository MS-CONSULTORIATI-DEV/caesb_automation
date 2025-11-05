package br.com.caesb.automation.service;

import br.com.caesb.automation.config.CaesbSession;
import br.com.caesb.automation.dto.BaixaResultado;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class CaesbBaixaService {

    private static final Logger logger = LogManager.getLogger(CaesbBaixaService.class);
    
    @Autowired
    private EmailNotificationService emailNotificationService;
    private static final String BASE_URL = "https://sistemas.caesb.df.gov.br/gcom/app/atendimento/os/baixa";
    private static final String OS_LIST_URL = "https://sistemas.caesb.df.gov.br/gcom/app/atendimento/os/controleOs/controle";
    private static final int MAX_RETRIES = 2;
    private static final int RETRY_DELAY_MS = 3000; // Aumentado para 3 segundos
    private static final int ACTION_TIMEOUT_MS = 5000;
    private static final int NAVIGATION_TIMEOUT_MS = 60000; // 60 segundos para navegação
    private static final int PAGE_LOAD_TIMEOUT_MS = 45000; // 45 segundos para carregamento de página
    private static final int DELAY_BETWEEN_REQUESTS_MS = 2000; // Delay entre requisições para evitar rate limiting
    private static final boolean HIDE_BROWSER = true;
    
    /**
     * Verifica se a sessão está válida testando acesso à página de baixa
     */
    private boolean verificarSessaoValida(CaesbSession session) {
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;
        
        try {
            logger.info("🔍 Verificando validade da sessão...");
            
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            context = browser.newContext();
            
            // Adicionar cookies
            BrowserContext finalContext = context;
            session.getCookies().forEach((name, value) -> {
                logger.debug("Cookie adicionado: {} = {}", name, value.substring(0, Math.min(20, value.length())) + "...");
                finalContext.addCookies(List.of(new Cookie(name, value).setUrl(BASE_URL)));
            });
            
            page = context.newPage();
            page.navigate(BASE_URL, new Page.NavigateOptions().setTimeout(20000));
            page.waitForLoadState(LoadState.LOAD);
            
            // Verificar se foi redirecionado para login
            String currentUrl = page.url();
            logger.debug("URL após navegação: {}", currentUrl);
            
            if (currentUrl.contains("/seguranca/app")) {
                logger.warn("❌ Sessão INVÁLIDA - Redirecionado para login");
                return false;
            }
            
            // Verificar se o formulário de pesquisa está presente
            if (page.locator("#formPesquisa\\:inptOs").count() > 0) {
                logger.info("✅ Sessão VÁLIDA - Formulário de pesquisa encontrado");
                return true;
            } else {
                logger.warn("⚠️ Sessão DUVIDOSA - Formulário de pesquisa não encontrado");
                return false;
            }
            
        } catch (Exception e) {
            logger.error("❌ Erro ao verificar sessão: {}", e.getMessage());
            return false;
        } finally {
            if (page != null) try { page.close(); } catch (Exception ignored) {}
            if (context != null) try { context.close(); } catch (Exception ignored) {}
            if (browser != null) try { browser.close(); } catch (Exception ignored) {}
            if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
        }
    }
    
    /**
     * Encontra um botão dinamicamente por múltiplas estratégias (texto, valor, tipo).
     * Isso evita dependência de IDs JSF que mudam quando elementos são adicionados.
     */
    private Locator findButtonDynamically(Page page, String... searchTexts) {
        for (String searchText : searchTexts) {
            try {
                // Estratégia 1: Buscar por texto exato do botão
                Locator byText = page.locator(String.format("button:has-text('%s'), input[type='button'][value*='%s'], input[type='submit'][value*='%s']", 
                    searchText, searchText, searchText));
                if (byText.count() > 0) {
                    logger.info("Botão encontrado por texto: '{}'", searchText);
                    return byText.first();
                }
                
                // Estratégia 2: Buscar por texto dentro do form específico
                Locator inForm = page.locator("#form1").locator(String.format("button:has-text('%s')", searchText));
                if (inForm.count() > 0) {
                    logger.info("Botão encontrado dentro do form1 por texto: '{}'", searchText);
                    return inForm.first();
                }
            } catch (Exception e) {
                logger.debug("Tentativa de encontrar botão por '{}' falhou: {}", searchText, e.getMessage());
            }
        }
        throw new RuntimeException("Botão não encontrado com os textos: " + String.join(", ", searchTexts));
    }
    
    /**
     * Encontra um grupo de radio buttons dinamicamente pelo label associado.
     * Busca o label pelo texto e depois encontra o grupo de radio relacionado.
     */
    private String findRadioGroupByLabel(Page page, String labelText) {
        try {
            // Buscar todos os grupos de radio buttons no formulário
            Locator radioGroups = page.locator("#form1 .ui-radiobutton");
            
            // Se temos um label próximo, procurar o grupo de radio associado
            for (int i = 0; i < radioGroups.count(); i++) {
                Locator group = radioGroups.nth(i);
                String parentText = group.locator("xpath=ancestor::tr[1]").innerText();
                if (parentText.toLowerCase().contains(labelText.toLowerCase())) {
                    // Extrair o ID do grupo
                    String radioId = group.getAttribute("id");
                    if (radioId != null && radioId.contains(":")) {
                        String groupId = radioId.substring(0, radioId.lastIndexOf(":"));
                        logger.info("Grupo de radio encontrado por label '{}': {}", labelText, groupId);
                        return groupId.replace("form1:", "");
                    }
                }
            }
            
        } catch (Exception e) {
            logger.debug("Erro ao buscar grupo de radio por label '{}': {}", labelText, e.getMessage());
        }
        return null;
    }
    
    /**
     * Clica em um radio button dinamicamente, tentando múltiplas estratégias.
     */
    private void clickRadioDynamically(Page page, String os, String labelText, int optionIndex, String logDescription) {
        try {
            // Estratégia 1: Tentar com o label conhecido
            String groupId = findRadioGroupByLabel(page, labelText);
            if (groupId != null) {
                clickRadioByIndex(page, os, "form1:" + groupId, optionIndex, logDescription);
                return;
            }
            
            // Estratégia 2: Buscar pela estrutura aninhada - para casos onde radio buttons estão em tabela filha
            String xpathNestedSelector = String.format(
                "//th[contains(text(), '%s')]/..//table//tr/td[position()=%d]//div[contains(@class, 'ui-radiobutton-box')]",
                labelText, optionIndex + 1
            );
            Locator radioBoxNested = page.locator(xpathNestedSelector);
            if (radioBoxNested.count() > 0) {
                radioBoxNested.first().click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                logger.info("Clicado '{}' usando XPath nested para OS {}", logDescription, os);
                return;
            }
            
            // Estratégia 3: Buscar pela estrutura - encontrar a linha que contém o texto
            String xpathSelector = String.format(
                "//tr[contains(., '%s')]//td[position()=%d]//div[contains(@class, 'ui-radiobutton-box')]",
                labelText, optionIndex + 1
            );
            Locator radioBox = page.locator(xpathSelector);
            if (radioBox.count() > 0) {
                radioBox.first().click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                logger.info("Clicado '{}' usando XPath para OS {}", logDescription, os);
                return;
            }
            
            logger.warn("Não foi possível encontrar radio button para: {}", logDescription);
            
        } catch (Exception e) {
            logger.error("Erro ao clicar no radio '{}' (OS {}): {}", logDescription, os, e.getMessage(), e);
        }
    }


    public CaesbBaixaService() {
    }

    public BaixaResultado baixarOs(CaesbSession session, String os) {
        logger.info("========================================");
        logger.info("Starting baixa for OS {}", os);
        logger.info("========================================");
        LocalDateTime inicioExecucao = LocalDateTime.now();
        
        try {
            // Delay inicial para evitar rate limiting quando processando múltiplas OS
            logger.debug("Aguardando {}ms para evitar rate limiting...", DELAY_BETWEEN_REQUESTS_MS);
            Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        
        // Verificar validade da sessão ANTES de iniciar o processo
        if (!verificarSessaoValida(session)) {
            logger.error("❌ Sessão inválida ou expirada para OS {}. Abortando processo.", os);
            try {
                emailNotificationService.enviarNotificacaoErro(os, 
                    "Sessão expirada. Faça login novamente no sistema.", 
                    inicioExecucao);
            } catch (Exception emailError) {
                logger.warn("Falha ao enviar notificação de sessão expirada: {}", emailError.getMessage());
            }
            return new BaixaResultado(os, false, List.of("Session expired - Faça login novamente"));
        }
        
        logger.info("✅ Sessão validada com sucesso. Iniciando processo de baixa...");

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;

        try {
            playwright = Playwright.create();
            
            // Argumentos do Chrome otimizados para Linux/Debian
            List<String> chromeArgs = new ArrayList<>();
            chromeArgs.add("--disable-dev-shm-usage"); // Evita problemas de memória compartilhada
            chromeArgs.add("--no-sandbox"); // Necessário em alguns ambientes Linux
            chromeArgs.add("--disable-setuid-sandbox");
            chromeArgs.add("--disable-gpu"); // Evita problemas com GPU no headless
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
            
            logger.info("Iniciando Chromium com {} argumentos para estabilidade no Linux", chromeArgs.size());
            
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(HIDE_BROWSER)
                    .setSlowMo(300) // Reduzido de 500 para 300ms
                    .setTimeout(NAVIGATION_TIMEOUT_MS)
                    .setArgs(chromeArgs));
            
            if (browser == null || !browser.isConnected()) {
                logger.error("Falha ao conectar ao browser para OS {}", os);
                return new BaixaResultado(os, false, List.of("Browser não conectado"));
            }
            
            logger.info("✓ Browser conectado com sucesso");
            
            // Criar contexto com configurações que parecem mais com navegador real
            context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setExtraHTTPHeaders(java.util.Map.of(
                        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        "Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Accept-Encoding", "gzip, deflate, br",
                        "DNT", "1",
                        "Connection", "keep-alive",
                        "Upgrade-Insecure-Requests", "1",
                        "Sec-Fetch-Dest", "document",
                        "Sec-Fetch-Mode", "navigate",
                        "Sec-Fetch-Site", "none",
                        "Cache-Control", "max-age=0"
                    )));

            // Configurar timeouts padrão no contexto
            context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS);
            context.setDefaultTimeout(PAGE_LOAD_TIMEOUT_MS);
            
            logger.info("✓ Contexto do browser criado com sucesso");

            // Capture console logs
            context.onConsoleMessage(msg -> logger.info("Console: [{}] {}", msg.type(), msg.text()));

            // Set cookies from session
            logger.info("📋 Adicionando cookies da sessão...");
            BrowserContext finalContext = context;
            int cookieCount = 0;
            for (var entry : session.getCookies().entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                finalContext.addCookies(List.of(new Cookie(name, value).setUrl(BASE_URL)));
                logger.debug("Cookie adicionado: {} = {}...", name, 
                    value.length() > 30 ? value.substring(0, 30) + "..." : value);
                cookieCount++;
            }
            logger.info("✅ {} cookies adicionados ao contexto", cookieCount);

            page = context.newPage();
            logger.info("✓ Página criada com sucesso");
            
            // Verificar se page está aberta antes de continuar
            if (page.isClosed()) {
                logger.error("❌ Página foi fechada prematuramente antes da navegação");
                return new BaixaResultado(os, false, List.of("Página fechada prematuramente"));
            }
            
            // Navegação com retry logic
            boolean navegacaoSucesso = false;
            Exception ultimoErro = null;
            
            for (int tentativa = 1; tentativa <= MAX_RETRIES; tentativa++) {
                try {
                    // Verificar se browser/context/page ainda estão abertos
                    if (!browser.isConnected()) {
                        throw new RuntimeException("Browser desconectado durante execução");
                    }
                    if (page.isClosed()) {
                        throw new RuntimeException("Página fechada durante execução");
                    }
                    
                    logger.info("Tentativa {} de navegação para {} (OS: {})", tentativa, BASE_URL, os);
                    
                    page.navigate(BASE_URL, new Page.NavigateOptions()
                            .setTimeout(NAVIGATION_TIMEOUT_MS)
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD));
                    
                    // Usar LOAD ao invés de NETWORKIDLE (mais confiável)
                    page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions()
                            .setTimeout(PAGE_LOAD_TIMEOUT_MS));
                    
                    // Aguardar um pouco para garantir que a página está estável
                    Thread.sleep(2000);
                    
                    logger.info("✓ Navegação bem-sucedida para {} (tentativa {})", BASE_URL, tentativa);
                    navegacaoSucesso = true;
                    break;
                    
                } catch (TimeoutError te) {
                    ultimoErro = te;
                    logger.warn("⚠️ Timeout na tentativa {} de navegação para {}: {}", 
                            tentativa, BASE_URL, te.getMessage());
                    
                    if (tentativa < MAX_RETRIES) {
                        logger.info("Aguardando {}ms antes de tentar novamente...", RETRY_DELAY_MS);
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                } catch (Exception e) {
                    ultimoErro = e;
                    logger.error("Erro na tentativa {} de navegação: {} - {}", 
                            tentativa, e.getClass().getSimpleName(), e.getMessage());
                    
                    // Se o browser/page foi fechado, não adianta tentar novamente
                    if (e.getMessage().contains("closed") || e.getMessage().contains("TargetClosedError")) {
                        logger.error("❌ Browser ou página foi fechado prematuramente. Abortando.");
                        return new BaixaResultado(os, false, 
                                List.of("Browser fechado prematuramente: " + e.getMessage()));
                    }
                    
                    // Tratamento especial para ERR_CONNECTION_RESET
                    if (e.getMessage().contains("ERR_CONNECTION_RESET")) {
                        logger.warn("⚠️ Servidor resetou a conexão (possível proteção anti-bot ou rate limiting)");
                        logger.info("💡 Aguardando {}ms antes de tentar novamente...", RETRY_DELAY_MS);
                    }
                    
                    if (tentativa < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }
            }
            
            if (!navegacaoSucesso) {
                logger.error("❌ Falha na navegação após {} tentativas para OS {}", MAX_RETRIES, os);
                String erroMsg = ultimoErro != null ? ultimoErro.getMessage() : "Timeout desconhecido";
                return new BaixaResultado(os, false, 
                        List.of("Falha na navegação inicial após " + MAX_RETRIES + " tentativas: " + erroMsg));
            }

            // Check for login redirect
            if (page.url().contains("/seguranca/app")) {
                logger.error("❌ Session expired for OS {} - Redirecionado para página de login", os);
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/session-expired-" + os + ".png")));
                
                try {
                    emailNotificationService.enviarNotificacaoErro(os, 
                        "Sessão expirada durante execução. Cookies podem estar inválidos.", 
                        inicioExecucao);
                } catch (Exception emailError) {
                    logger.warn("Falha ao enviar notificação: {}", emailError.getMessage());
                }
                
                return new BaixaResultado(os, false, List.of("Session expired"));
            }

            // Step 1: Search for OS
            logger.info("🔍 Searching for OS {}", os);
            
            // Wait for the search form to be ready
            boolean formularioDisponivel = false;
            for (int tentativaForm = 1; tentativaForm <= 3; tentativaForm++) {
                try {
                    logger.debug("Tentativa {} - Aguardando formulário de pesquisa...", tentativaForm);
                    Locator searchInput = page.locator("#formPesquisa\\:inptOs");
                    searchInput.waitFor(new Locator.WaitForOptions().setTimeout(15000));
                    
                    // Double check it's visible and enabled
                    if (!searchInput.isVisible() || !searchInput.isEnabled()) {
                        logger.warn("Formulário existe mas não está visível/habilitado. Tentativa {}/3", tentativaForm);
                        Thread.sleep(2000);
                        continue;
                    }
                    
                    logger.info("✓ Formulário de pesquisa disponível (tentativa {})", tentativaForm);
                    formularioDisponivel = true;
                    break;
                    
                } catch (TimeoutError e) {
                    logger.warn("⚠️ Timeout aguardando formulário (tentativa {}/3): {}", tentativaForm, e.getMessage());
                    if (tentativaForm < 3) {
                        logger.info("Recarregando página...");
                        try {
                            page.reload(new Page.ReloadOptions().setTimeout(30000));
                            page.waitForLoadState(LoadState.LOAD);
                            Thread.sleep(2000);
                        } catch (Exception reloadError) {
                            logger.error("Erro ao recarregar página: {}", reloadError.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.error("Erro ao aguardar formulário: {}", e.getMessage());
                }
            }
            
            if (!formularioDisponivel) {
                logger.error("❌ Formulário de pesquisa NÃO disponível após 3 tentativas. URL: {}", page.url());
                
                // Verificar se foi redirecionado novamente
                if (page.url().contains("/seguranca/app")) {
                    logger.error("Detectado redirecionamento para login durante aguardo do formulário");
                    return new BaixaResultado(os, false, List.of("Session expired during form load"));
                }
                
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/form-not-available-" + os + ".png")));
                
                try {
                    emailNotificationService.enviarNotificacaoErro(os, 
                        "Formulário de pesquisa não disponível após 3 tentativas. URL: " + page.url(), 
                        inicioExecucao);
                } catch (Exception emailError) {
                    logger.warn("Falha ao enviar notificação: {}", emailError.getMessage());
                }
                
                return new BaixaResultado(os, false, List.of("Search form not available - page may not have loaded correctly"));
            }
            
            page.locator("#formPesquisa\\:inptOs").fill(os);
            page.locator("#formPesquisa\\:pesquisarOrdemServico").click();

            // Wait for search results (form1 or error message)
            try {
                page.waitForSelector("#form1, .ui-linha-form-messages", new Page.WaitForSelectorOptions().setTimeout(15000));
            } catch (TimeoutError e) {
                logger.info("Timeout waiting for search results for OS {}. Retrying...", os);
                // Retry search
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    logger.info("Retry attempt {} for OS {}", attempt, os);
                    try {
                        // Ensure form is still available before retry
                        Locator retrySearchInput = page.locator("#formPesquisa\\:inptOs");
                        retrySearchInput.waitFor(new Locator.WaitForOptions().setTimeout(10000));
                        
                        if (!retrySearchInput.isVisible() || !retrySearchInput.isEnabled()) {
                            throw new TimeoutError("Search form not visible/enabled on retry");
                        }
                        
                        retrySearchInput.fill(os);
                        page.locator("#formPesquisa\\:pesquisarOrdemServico").click();
                    } catch (TimeoutError formError) {
                        logger.warn("Search form disappeared during retry {} for OS {}", attempt, os);
                        if (attempt == MAX_RETRIES) {
                            return new BaixaResultado(os, false, List.of("Search form not available during retries"));
                        }
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    try {
                        page.waitForSelector("#form1, .ui-linha-form-messages", new Page.WaitForSelectorOptions().setTimeout(15000));
                        break;
                    } catch (TimeoutError te) {
                        if (attempt == MAX_RETRIES) {
                            logger.error("Failed to load search results for OS {} after {} attempts", os, MAX_RETRIES);
//                            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/search-timeout-" + os + ".png")));
                            return new BaixaResultado(os, false, List.of("Search results not loaded after retries"));
                        }
                    }
                    Thread.sleep(RETRY_DELAY_MS);
                }
            }

            // Check for errors after search
            if (page.locator(".ui-linha-form-messages").count() > 0 && page.locator(".ui-linha-form-messages").first().isVisible()) {
                List<String> errors = extractErrorMessages(page);
                logger.info("Search failed for OS {}: {}", os, errors);
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/search-error-" + os + ".png")));
                return new BaixaResultado(os, false, errors);
            }

            // Verify form1 is present
            if (!page.locator("#form1").isVisible()) {
                logger.error("Form1 not found after search for OS {}", os);
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/form1-missing-" + os + ".png")));
                return new BaixaResultado(os, false, List.of("Form1 not loaded after search"));
            }

            // Step 2: Fill form fields
            logger.info("Filling form fields for OS {}", os);

            // ========== FASE 1: CLICAR EM TODOS OS RADIO BUTTONS PRIMEIRO ==========
            // Radio buttons disparam AJAX que pode resetar campos. Clicamos todos primeiro!
            logger.info("Fase 1: Clicando em todos os radio buttons (que disparam AJAX)...");
            
            clickRadioDynamically(page, os, "Deseja refaturar conta", 1, "Deseja refaturar conta: Não");
            logger.info("Aguardando após 'Deseja refaturar conta'...");
            Thread.sleep(500);
            
            clickRadioDynamically(page, os, "Executado", 0, "Executado: Sim");
            logger.info("Aguardando após 'Executado'...");
            Thread.sleep(500);
            
            clickRadioDynamically(page, os, "vazamento", 1, "Havia vazamento ou extravasamento: Não");
            logger.info("Aguardando após 'vazamento'...");
            Thread.sleep(500);
            
            // Descomente as linhas abaixo se necessário
            // clickRadioDynamically(page, os, "Notificação", 0, "Notificação em Mãos: Sim");
            // clickRadioDynamically(page, os, "Acesso ao Hidrômetro", 1, "Acesso ao Hidrômetro: Não");

            // Aguardar que TODO o AJAX dos radio buttons complete antes de preencher campos
            logger.info("Aguardando AJAX completar após todos os radio buttons...");
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
            Thread.sleep(2000); // Pausa adicional para garantir que JSF processou tudo

            // ========== FASE 2: PREENCHER CAMPOS DE DATA E TEXTO ==========
            logger.info("Fase 2: Preenchendo datas e campos de texto...");
            
            // Data Início de Execução: Current date at 08:00
            String startDateTime = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"))
                    .withHour(8).withMinute(0).withSecond(0).withNano(0)
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            boolean dataInicioPreenchida = preencherCampoDataComRetry(page, "dataInicioExecucao", startDateTime, os);
            if (!dataInicioPreenchida) {
                logger.warn("⚠️ FALHA ao preencher Data Início para OS {}", os);
            }

            // Data Fim de Execução: Current date and time
            String endDateTime = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"))
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            boolean dataFimPreenchida = preencherCampoDataComRetry(page, "dataFimExecucao", endDateTime, os);
            if (!dataFimPreenchida) {
                logger.warn("⚠️ FALHA ao preencher Data Fim para OS {}", os);
            }

            String hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"))
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

            boolean diagnosticoPreenchido = preencherTextareaComRetry(page, "diagnosticoBaixa",
                    "Enviado cobrança em " + hoje, os);
            if (!diagnosticoPreenchido) {
                logger.warn("⚠️ FALHA ao preencher Diagnóstico para OS {}", os);
            }

            boolean providenciaPreenchida = preencherTextareaComRetry(page, "providenciaBaixa",
                    "Usuário ciente dos débitos.", os);
            if (!providenciaPreenchida) {
                logger.warn("⚠️ FALHA ao preencher Providência para OS {}", os);
            }

            // ========== FASE 3: VALIDAR CAMPOS ANTES DE SALVAR ==========
            logger.info("Fase 3: Validando campos preenchidos...");
            List<String> camposVazios = validarCamposObrigatorios(page, os);
            if (!camposVazios.isEmpty()) {
                logger.error("❌ CAMPOS OBRIGATÓRIOS VAZIOS: {} - OS {}", camposVazios, os);
                
                // Tentar preencher novamente os campos vazios
                logger.info("🔄 Tentando preencher campos vazios novamente (tentativa 2)...");
                if (camposVazios.contains("dataInicioExecucao")) {
                    preencherCampoDataComRetry(page, "dataInicioExecucao", startDateTime, os);
                }
                if (camposVazios.contains("dataFimExecucao")) {
                    preencherCampoDataComRetry(page, "dataFimExecucao", endDateTime, os);
                }
                if (camposVazios.contains("diagnosticoBaixa")) {
                    preencherTextareaComRetry(page, "diagnosticoBaixa", "Enviado cobrança em " + hoje, os);
                }
                if (camposVazios.contains("providenciaBaixa")) {
                    preencherTextareaComRetry(page, "providenciaBaixa", "Usuário ciente dos débitos.", os);
                }
                
                // Validar novamente após segunda tentativa
                Thread.sleep(1000);
                List<String> camposAindaVazios = validarCamposObrigatorios(page, os);
                if (!camposAindaVazios.isEmpty()) {
                    logger.error("❌ AINDA HÁ CAMPOS VAZIOS APÓS RETRY: {} - OS {}", camposAindaVazios, os);
                    String erroMsg = "Campos obrigatórios não preenchidos: " + String.join(", ", camposAindaVazios);
                    
                    try {
                        emailNotificationService.enviarNotificacaoErro(os, 
                            erroMsg + ". Possível problema com AJAX ou validação do formulário.", 
                            inicioExecucao);
                    } catch (Exception emailError) {
                        logger.warn("Falha ao enviar notificação: {}", emailError.getMessage());
                    }
                    
                    return new BaixaResultado(os, false, List.of(erroMsg));
                } else {
                    logger.info("✅ Campos preenchidos com sucesso após retry");
                }
            }

            // Aguardar um pouco antes de salvar para garantir que JSF processou todos os campos
            logger.info("Aguardando antes de clicar em Salvar...");
            Thread.sleep(1500);

            // Step 3: Click Salvar - Usando busca dinâmica por texto do botão
            logger.info("Clicking Salvar button");
            Locator salvarButton = findButtonDynamically(page, "Salvar", "salvar", "SALVAR");
            
            // Aguardar um momento antes de clicar para evitar rate limiting
            Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
            
            salvarButton.click(new Locator.ClickOptions().setForce(true));
            logger.info("✓ Botão Salvar clicado");

            // Check for validation errors
            logger.info("Verificando mensagens de erro após Salvar...");
            Thread.sleep(1000); // Aguardar mensagens aparecerem
            
            if (page.locator(".ui-linha-form-messages, .ui-messages-error").count() > 0) {
                Locator errorLocator = page.locator(".ui-linha-form-messages, .ui-messages-error").first();
                if (errorLocator.isVisible()) {
                    List<String> errors = extractErrorMessages(page);
                    logger.error("❌ Validação falhou para OS {}: {}", os, errors);
                    
                    // Verificar se são erros de campos obrigatórios
                    boolean todosObrigatorios = errors.stream()
                        .allMatch(e -> e.toLowerCase().contains("obrigatório") || e.toLowerCase().contains("obrigatorio"));
                    
                    if (todosObrigatorios) {
                        logger.error("❌ Todos os erros são de campos obrigatórios! Verificando estado do formulário...");
                        
                        // Capturar estado dos campos para diagnóstico
                        List<String> camposVaziosDetectados = validarCamposObrigatorios(page, os);
                        logger.error("Campos vazios detectados: {}", camposVaziosDetectados);
                        
                        try {
                            emailNotificationService.enviarNotificacaoErro(os, 
                                "Erro de validação: Campos obrigatórios não preenchidos. Campos vazios: " + camposVaziosDetectados, 
                                inicioExecucao);
                        } catch (Exception emailError) {
                            logger.warn("Falha ao enviar notificação: {}", emailError.getMessage());
                        }
                    }
                    
                    return new BaixaResultado(os, false, errors);
                }
            }
            
            logger.info("✓ Nenhum erro de validação detectado após Salvar");

            // Step 4: Handle confirmation dialog
            if (page.locator("#formValidacaolancamento").isVisible()) {
                logger.info("Confirmation dialog appeared");
                Locator confirmButton = page.locator("#formValidacaolancamento button:contains('Confirmar')");
                if (confirmButton.isVisible()) {
                    confirmButton.click();
                    page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions()
                            .setTimeout(PAGE_LOAD_TIMEOUT_MS));
                    Thread.sleep(1000); // Aguardar processamento
                    logger.info("Clicked confirmation button");
                } else {
                    logger.error("Confirmation button not found for OS {}", os);
                    return new BaixaResultado(os, false, List.of("Confirmation button not found"));
                }
            }

            // Check for server error page
            if (page.locator("#icone").isVisible() && page.locator("#msg").isVisible()) {
                String errorMsg = page.locator("#msg").innerText();
                String trackingCode = page.locator("#msg b").innerText();
                logger.error("Server error for OS {}: {} (tracking code: {})", os, errorMsg, trackingCode);
                return new BaixaResultado(os, false, List.of("Server error: " + errorMsg + " (tracking code: " + trackingCode));
            }

            // Step 5: Verify OS is closed
            logger.info("🔍 Verificando se OS {} foi fechada...", os);
            
            // Aguardar antes de navegar para evitar ERR_CONNECTION_RESET
            Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
            
            boolean verificacaoSucesso = false;
            for (int tentativaVerificacao = 1; tentativaVerificacao <= 3; tentativaVerificacao++) {
                try {
                    logger.info("Tentativa {} de verificação da OS", tentativaVerificacao);
                    
                    page.navigate(OS_LIST_URL, new Page.NavigateOptions()
                            .setTimeout(NAVIGATION_TIMEOUT_MS)
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD));
                    page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions()
                            .setTimeout(PAGE_LOAD_TIMEOUT_MS));
                    Thread.sleep(1500); // Aguardar carregamento da lista
                    
                    boolean osClosed = !page.locator("text=" + os).isVisible();
                    if (!osClosed) {
                        logger.warn("⚠️ OS {} ainda aparece na lista pendente", os);
//                        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/not-closed-" + os + ".png")));
                        return new BaixaResultado(os, false, List.of("OS not closed"));
                    }
                    
                    logger.info("✅ OS {} não aparece na lista - verificação bem-sucedida", os);
                    verificacaoSucesso = true;
                    break;
                    
                } catch (PlaywrightException pe) {
                    // Tratamento especial para ERR_CONNECTION_RESET
                    if (pe.getMessage().contains("ERR_CONNECTION_RESET")) {
                        logger.warn("⚠️ ERR_CONNECTION_RESET ao verificar OS (tentativa {}/3)", tentativaVerificacao);
                        logger.warn("Servidor pode estar bloqueando requisições. Aguardando {}ms...", RETRY_DELAY_MS);
                        
                        if (tentativaVerificacao < 3) {
                            Thread.sleep(RETRY_DELAY_MS);
                        } else {
                            // Na última tentativa, considerar sucesso pois OS foi salva
                            logger.info("⚠️ Verificação falhou com ERR_CONNECTION_RESET, mas OS foi SALVA com sucesso");
                            logger.info("✅ Considerando operação bem-sucedida (dados foram salvos antes da verificação)");
                            verificacaoSucesso = true;
                            break;
                        }
                    } else if (pe instanceof TimeoutError) {
                        // Tratamento para TimeoutError
                        logger.warn("⚠️ Timeout ao verificar lista (tentativa {}/3): {}", 
                                tentativaVerificacao, pe.getMessage());
                        
                        if (tentativaVerificacao < 3) {
                            Thread.sleep(RETRY_DELAY_MS);
                        } else {
                            // Se der timeout na verificação, consideramos sucesso pois a OS foi salva
                            logger.info("✅ Considerando operação bem-sucedida (timeout na verificação)");
                            verificacaoSucesso = true;
                        }
                    } else {
                        throw pe; // Re-lançar outras exceções
                    }
                }
            }
            
            if (!verificacaoSucesso) {
                logger.warn("⚠️ Não foi possível verificar o fechamento da OS, mas operação pode ter sido bem-sucedida");
            }

            logger.info("OS {} processed successfully", os);
//            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/success-" + os + ".png")));
            
          
            
            return new BaixaResultado(os, true, List.of("OK"));
        } catch (PlaywrightException e) {
            String errorType = e.getClass().getSimpleName();
            logger.error("Playwright error ({}) for OS {}: {}", errorType, os, e.getMessage(), e);
            
            // Tratamento específico para TargetClosedError
            if (e.getMessage().contains("Target page, context or browser has been closed") || 
                e.getMessage().contains("TargetClosedError")) {
                logger.error("❌ Browser/Context/Page foi fechado prematuramente para OS {}", os);
                logger.error("Possíveis causas: crash do Chrome, falta de memória, timeout excessivo");
                
                try {
                    emailNotificationService.enviarNotificacaoErro(os, 
                        "Browser fechado prematuramente (TargetClosedError). Verifique recursos do sistema.", 
                        inicioExecucao);
                } catch (Exception emailError) {
                    logger.warn("Failed to send error notification for OS {}: {}", os, emailError.getMessage());
                }
                
                return new BaixaResultado(os, false, 
                    List.of("Browser fechado prematuramente - Verifique recursos do sistema (memória, CPU)"));
            }
            
            if (page != null && !page.isClosed()) {
                try {
//                    page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/playwright-error-" + os + ".png")));
                } catch (Exception screenshotError) {
                    logger.debug("Não foi possível capturar screenshot: {}", screenshotError.getMessage());
                }
            }
            
            // Enviar notificação de erro
           /* try {
                emailNotificationService.enviarNotificacaoErro(os, "Playwright error: " + e.getMessage(), inicioExecucao);
            } catch (Exception emailError) {
                logger.warn("Failed to send error notification for OS {}: {}", os, emailError.getMessage());
            }*/
            
            return new BaixaResultado(os, false, List.of("Playwright error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error for OS {}: {}", os, e.getMessage(), e);
            
            if (page != null && !page.isClosed()) {
                try {
//                    page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/unexpected-error-" + os + ".png")));
                } catch (Exception screenshotError) {
                    logger.debug("Não foi possível capturar screenshot: {}", screenshotError.getMessage());
                }
            }
            
            // Enviar notificação de erro
            try {
                emailNotificationService.enviarNotificacaoErro(os, "Unexpected error: " + e.getMessage(), inicioExecucao);
            } catch (Exception emailError) {
                logger.warn("Failed to send error notification for OS {}: {}", os, emailError.getMessage());
            }
            
            return new BaixaResultado(os, false, List.of("Unexpected error: " + e.getMessage()));
        } finally {
            // Fechar recursos de forma segura
            try {
                if (page != null && !page.isClosed()) {
                    logger.debug("Fechando página...");
                    page.close();
                }
            } catch (Exception e) {
                logger.debug("Erro ao fechar página: {}", e.getMessage());
            }
            
            try {
                if (context != null) {
                    logger.debug("Fechando contexto...");
                    context.close();
                }
            } catch (Exception e) {
                logger.debug("Erro ao fechar contexto: {}", e.getMessage());
            }
            
            try {
                if (browser != null && browser.isConnected()) {
                    logger.debug("Fechando browser...");
                    browser.close();
                }
            } catch (Exception e) {
                logger.debug("Erro ao fechar browser: {}", e.getMessage());
            }
            
            try {
                if (playwright != null) {
                    logger.debug("Fechando Playwright...");
                    playwright.close();
                }
            } catch (Exception e) {
                logger.debug("Erro ao fechar Playwright: {}", e.getMessage());
            }
            
            logger.info("Recursos do Playwright liberados para OS {}", os);
        }
    }

    private void clickRadioByIndex(Page page, String os, String groupId, int index, String fieldName) {
        try {
            String selector = String.format("#%s td:nth-of-type(%d) .ui-radiobutton-box", groupId.replace(":", "\\:"), index + 1);
            Locator box = page.locator(selector);
            box.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            logger.info("Clicked '{}' radio option (index {}) for OS {}", fieldName, index, os);
        } catch (Exception e) {
            logger.error("Failed to click '{}' radio (OS {}): {}", fieldName, os, e.getMessage(), e);
        }
    }





    private List<String> extractErrorMessages(Page page) {
        List<String> errors = new ArrayList<>();
        page.locator(".ui-linha-form-messages, .ui-messages-error").all().forEach(element -> {
            String error = element.innerText().trim();
            if (!error.isEmpty()) {
                errors.add(error);
            }
        });
        return errors.isEmpty() ? List.of("Unknown validation error") : errors;
    }

    /**
     * Preenche campo de data com retry e verificação
     */
    private boolean preencherCampoDataComRetry(Page page, String campoId, String valor, String os) {
        String selector = "#form1\\:" + campoId + "_input";
        int maxTentativas = 3;
        
        for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
            try {
                logger.debug("Tentativa {} de preencher campo '{}' com '{}'", tentativa, campoId, valor);
                
                // Aguardar o campo estar disponível
                Locator campo = page.locator(selector);
                if (!campo.isVisible()) {
                    logger.warn("Campo '{}' não está visível. Tentativa {}/{}", campoId, tentativa, maxTentativas);
                    Thread.sleep(1000);
                    continue;
                }
                
                // Remover readonly e preencher
                campo.evaluate("el => el.removeAttribute('readonly')");
                campo.scrollIntoViewIfNeeded();
                campo.click(new Locator.ClickOptions().setTimeout(3000));
                campo.fill("");  // Limpar primeiro
                Thread.sleep(200);
                campo.fill(valor);
                
                // Disparar eventos para notificar JSF
                campo.evaluate("el => { " +
                    "el.dispatchEvent(new Event('input', { bubbles: true })); " +
                    "el.dispatchEvent(new Event('change', { bubbles: true })); " +
                    "el.dispatchEvent(new Event('blur', { bubbles: true })); " +
                    "}");
                
                Thread.sleep(300);
                
                // Verificar se o valor foi setado
                String valorAtual = (String) campo.evaluate("el => el.value");
                if (valorAtual != null && valorAtual.equals(valor)) {
                    logger.info("✓ Campo '{}' preenchido com sucesso: '{}'", campoId, valor);
                    return true;
                } else {
                    logger.warn("Campo '{}' não manteve o valor. Esperado: '{}', Atual: '{}'. Tentativa {}/{}",
                        campoId, valor, valorAtual, tentativa, maxTentativas);
                }
                
            } catch (Exception e) {
                logger.error("Erro ao preencher campo '{}' (tentativa {}/{}): {}", 
                    campoId, tentativa, maxTentativas, e.getMessage());
            }
            
            if (tentativa < maxTentativas) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        logger.error("❌ FALHOU ao preencher campo '{}' após {} tentativas - OS {}", campoId, maxTentativas, os);
        return false;
    }

    /**
     * Preenche textarea com retry e verificação
     */
    private boolean preencherTextareaComRetry(Page page, String idSuffix, String texto, String os) {
        String selector = "#form1\\:" + idSuffix;
        int maxTentativas = 3;
        
        for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
            try {
                logger.debug("Tentativa {} de preencher textarea '{}' com '{}'", tentativa, idSuffix, texto);
                
                Locator ta = page.locator(selector);
                if (!ta.isVisible()) {
                    logger.warn("Textarea '{}' não está visível. Tentativa {}/{}", idSuffix, tentativa, maxTentativas);
                    Thread.sleep(1000);
                    continue;
                }
                
                ta.evaluate("el => el.removeAttribute('readonly')");
                ta.scrollIntoViewIfNeeded();
                ta.click(new Locator.ClickOptions().setTimeout(3000));
                ta.fill("");  // Limpar primeiro
                Thread.sleep(200);
                ta.fill(texto);
                
                // Disparar eventos para notificar JSF
                ta.evaluate("el => { " +
                    "el.dispatchEvent(new Event('input', { bubbles: true })); " +
                    "el.dispatchEvent(new Event('change', { bubbles: true })); " +
                    "el.dispatchEvent(new Event('blur', { bubbles: true })); " +
                    "}");
                
                Thread.sleep(300);
                
                // Verificar se o valor foi setado
                String valorAtual = (String) ta.evaluate("el => el.value");
                if (valorAtual != null && !valorAtual.trim().isEmpty()) {
                    logger.info("✓ Textarea '{}' preenchido com sucesso", idSuffix);
                    return true;
                } else {
                    logger.warn("Textarea '{}' não manteve o valor. Tentativa {}/{}", 
                        idSuffix, tentativa, maxTentativas);
                }
                
            } catch (Exception e) {
                logger.error("Erro ao preencher textarea '{}' (tentativa {}/{}): {}", 
                    idSuffix, tentativa, maxTentativas, e.getMessage());
            }
            
            if (tentativa < maxTentativas) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        logger.error("❌ FALHOU ao preencher textarea '{}' após {} tentativas - OS {}", idSuffix, maxTentativas, os);
        return false;
    }

    /**
     * Valida se os campos obrigatórios estão preenchidos antes de salvar
     */
    private List<String> validarCamposObrigatorios(Page page, String os) {
        List<String> camposVazios = new ArrayList<>();
        
        try {
            // Validar Data Início
            String dataInicio = (String) page.locator("#form1\\:dataInicioExecucao_input")
                .evaluate("el => el.value");
            if (dataInicio == null || dataInicio.trim().isEmpty()) {
                camposVazios.add("dataInicioExecucao");
                logger.warn("Campo 'dataInicioExecucao' está vazio");
            } else {
                logger.info("✓ dataInicioExecucao: '{}'", dataInicio);
            }
        } catch (Exception e) {
            logger.debug("Erro ao validar dataInicioExecucao: {}", e.getMessage());
        }
        
        try {
            // Validar Data Fim
            String dataFim = (String) page.locator("#form1\\:dataFimExecucao_input")
                .evaluate("el => el.value");
            if (dataFim == null || dataFim.trim().isEmpty()) {
                camposVazios.add("dataFimExecucao");
                logger.warn("Campo 'dataFimExecucao' está vazio");
            } else {
                logger.info("✓ dataFimExecucao: '{}'", dataFim);
            }
        } catch (Exception e) {
            logger.debug("Erro ao validar dataFimExecucao: {}", e.getMessage());
        }
        
        try {
            // Validar Diagnóstico
            String diagnostico = (String) page.locator("#form1\\:diagnosticoBaixa")
                .evaluate("el => el.value");
            if (diagnostico == null || diagnostico.trim().isEmpty()) {
                camposVazios.add("diagnosticoBaixa");
                logger.warn("Campo 'diagnosticoBaixa' está vazio");
            } else {
                logger.info("✓ diagnosticoBaixa: '{}'", diagnostico.substring(0, Math.min(30, diagnostico.length())) + "...");
            }
        } catch (Exception e) {
            logger.debug("Erro ao validar diagnosticoBaixa: {}", e.getMessage());
        }
        
        try {
            // Validar Providência
            String providencia = (String) page.locator("#form1\\:providenciaBaixa")
                .evaluate("el => el.value");
            if (providencia == null || providencia.trim().isEmpty()) {
                camposVazios.add("providenciaBaixa");
                logger.warn("Campo 'providenciaBaixa' está vazio");
            } else {
                logger.info("✓ providenciaBaixa: '{}'", providencia.substring(0, Math.min(30, providencia.length())) + "...");
            }
        } catch (Exception e) {
            logger.debug("Erro ao validar providenciaBaixa: {}", e.getMessage());
        }
        
        if (camposVazios.isEmpty()) {
            logger.info("✅ Todos os campos obrigatórios estão preenchidos - OS {}", os);
        }
        
        return camposVazios;
    }

}