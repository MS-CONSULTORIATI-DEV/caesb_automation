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
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000;
    private static final int ACTION_TIMEOUT_MS = 5000;
    private static final boolean HIDE_BROWSER = true;
    
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
        logger.info("Starting baixa for OS {}", os);
        LocalDateTime inicioExecucao = LocalDateTime.now();

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;

        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(HIDE_BROWSER) // Show browser UI for debugging
                    .setSlowMo(500));   // 500ms delay between actions
            context = browser.newContext(new Browser.NewContextOptions());


            // Capture console logs
            context.onConsoleMessage(msg -> logger.info("Console: [{}] {}", msg.type(), msg.text()));

            // Set cookies from session
            BrowserContext finalContext = context;
            session.getCookies().forEach((name, value) ->
                    finalContext.addCookies(List.of(new Cookie(name, value).setUrl(BASE_URL))));

            page = context.newPage();
            logger.info("Navigating to {}", BASE_URL);
            page.navigate(BASE_URL);
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(30000));

            // Check for login redirect
            if (page.url().contains("/seguranca/app")) {
                logger.error("Session expired for OS {}", os);
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/session-expired-" + os + ".png")));
                return new BaixaResultado(os, false, List.of("Session expired"));
            }

            // Step 1: Search for OS
            logger.info("Searching for OS {}", os);
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
                    page.locator("#formPesquisa\\:inptOs").fill(os);
                    page.locator("#formPesquisa\\:pesquisarOrdemServico").click();
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

            // Usando seletores dinâmicos que não dependem de IDs JSF mutáveis
            clickRadioDynamically(page, os, "Deseja refaturar conta", 1, "Deseja refaturar conta: Não");
            clickRadioDynamically(page, os, "Executado", 0, "Executado: Sim");

            // Data Início de Execução: Current date at 08:00
            String startDateTime = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"))
                    .withHour(8).withMinute(0).withSecond(0).withNano(0)
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            if (page.locator("#form1\\:dataInicioExecucao_input").isVisible()) {
                page.locator("#form1\\:dataInicioExecucao_input").evaluate("el => el.removeAttribute('readonly')");
                page.locator("#form1\\:dataInicioExecucao_input").fill(startDateTime);
                logger.info("Set dataInicioExecucao to {}", startDateTime);
            } else {
                logger.info("Data Início field not found for OS {}", os);
            }

            // Data Fim de Execução: Current date and time
            String endDateTime = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"))
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            if (page.locator("#form1\\:dataFimExecucao_input").isVisible()) {
                page.locator("#form1\\:dataFimExecucao_input").evaluate("el => el.removeAttribute('readonly')");
                page.locator("#form1\\:dataFimExecucao_input").fill(startDateTime);
                logger.info("Set dataFimExecucao to {}", endDateTime);
            } else {
                logger.info("Data Fim field not found for OS {}", os);
            }

            clickRadioDynamically(page, os, "vazamento", 1, "Havia vazamento ou extravasamento: Não");
            // Descomente as linhas abaixo se necessário
            // clickRadioDynamically(page, os, "Notificação", 0, "Notificação em Mãos: Sim");
            // clickRadioDynamically(page, os, "Acesso ao Hidrômetro", 1, "Acesso ao Hidrômetro: Não");

            String hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"))
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

            preencherTextarea(page, "diagnosticoBaixa",
                    "Enviado cobrança em " + hoje);

            preencherTextarea(page, "providenciaBaixa",
                    "Usuário ciente dos débitos.");

            // Step 3: Click Salvar - Usando busca dinâmica por texto do botão
            logger.info("Clicking Salvar button");
            Locator salvarButton = findButtonDynamically(page, "Salvar", "salvar", "SALVAR");
            salvarButton.click(new Locator.ClickOptions().setForce(true));

            // Check for validation errors
            if (page.locator(".ui-linha-form-messages").count() > 0 && page.locator(".ui-linha-form-messages").first().isVisible()) {
                List<String> errors = extractErrorMessages(page);
                logger.info("Validation failed for OS {}: {}", os, errors);
                return new BaixaResultado(os, false, errors);
            }

            // Step 4: Handle confirmation dialog
            if (page.locator("#formValidacaolancamento").isVisible()) {
                logger.info("Confirmation dialog appeared");
                Locator confirmButton = page.locator("#formValidacaolancamento button:contains('Confirmar')");
                if (confirmButton.isVisible()) {
                    confirmButton.click();
                    page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(30000));
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
            logger.info("Verifying OS {} is closed", os);
            page.navigate(OS_LIST_URL);
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(30000));
            boolean osClosed = !page.locator("text=" + os).isVisible();
            if (!osClosed) {
                logger.info("OS {} still appears in pending list", os);
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/not-closed-" + os + ".png")));
                return new BaixaResultado(os, false, List.of("OS not closed"));
            }

            logger.info("OS {} processed successfully", os);
//            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/success-" + os + ".png")));
            
          
            
            return new BaixaResultado(os, true, List.of("OK"));
        } catch (PlaywrightException e) {
            logger.error("Playwright error for OS {}: {}", os, e.getMessage(), e);
            if (page != null) {
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/playwright-error-" + os + ".png")));
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
            if (page != null) {
//                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("screenshots/unexpected-error-" + os + ".png")));
            }
            
            // Enviar notificação de erro
            try {
                emailNotificationService.enviarNotificacaoErro(os, "Unexpected error: " + e.getMessage(), inicioExecucao);
            } catch (Exception emailError) {
                logger.warn("Failed to send error notification for OS {}: {}", os, emailError.getMessage());
            }
            
            return new BaixaResultado(os, false, List.of("Unexpected error: " + e.getMessage()));
        } finally {
            if (page != null) {
                page.close();
            }
            if (context != null) {
                context.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
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
     * Preenche um <textarea> dinamicamente.
     *
     * @param page        instância Page do Playwright
     * @param idSuffix    parte após o “:” (por ex.  "diagnosticoBaixa")
     * @param texto       conteúdo a ser escrito
     */
    private void preencherTextarea(Page page, String idSuffix, String texto) {
        try {
            // id completo é sempre "form1:algumaCoisa"
            String idCompleto = "form1:" + idSuffix;

            // selector escapado para Playwright  (#form1\:diagnosticoBaixa)
            String selector   = "#" + idCompleto.replace(":", "\\:");

            Locator ta = page.locator(selector);

            // textarea vem com readonly.  Remove-o antes de digitar
            ta.evaluate("el => el.removeAttribute('readonly')");

            // garante visibilidade e foco
            ta.scrollIntoViewIfNeeded();
            ta.click(new Locator.ClickOptions().setTimeout(3000));

            // limpa e escreve
            ta.fill(texto);

            logger.info("Textarea '{}' preenchido com: {}", idSuffix, texto);
        } catch (Exception e) {
            logger.info("Falha ao preencher textarea '{}' : {}", idSuffix, e.getMessage(), e);
        }
    }

}