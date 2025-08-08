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
        logger.info("Starting login process...");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            logger.info("Browser launched in headless mode.");

            Page page = browser.newPage();
            logger.info("Navigating to login page...");
            page.navigate("https://sistemas.caesb.df.gov.br/seguranca/app/");

            logger.info("Filling in username and password.");
            page.locator("#j_username").fill(properties.getUsername());
            page.locator("#j_password").fill(properties.getPassword());
            page.locator("#btEntrar").click();
            logger.info("Login form submitted.");

            page.waitForLoadState(LoadState.NETWORKIDLE);
            logger.info("Waited for page to load.");

            page.navigate("https://sistemas.caesb.df.gov.br/gcom/app/atendimento/os/controleOs/controle");
            logger.info("Navigated to OS control page.");

            if (!page.url().contains("/controleOs/controle")) {
                logger.error("Failed to navigate to OS control page. Login probably failed.");
                throw new IllegalStateException("Falha ao navegar para a página de controle de OS. Login provavelmente falhou.");
            }

            String viewState = page.locator("input[name='javax.faces.ViewState']").first().getAttribute("value");
            logger.info("Retrieved ViewState: {}", viewState);

            String url = page.url();
            String execution = url.replaceAll(".*execution=([^&]+).*", "$1");
            logger.info("Retrieved execution parameter: {}", execution);

            logger.info("Retrieving cookies...");
            java.util.List<com.microsoft.playwright.options.Cookie> cookies = page.context().cookies();
            java.util.Map<String, String> cookieMap = new java.util.HashMap<>();
            for (com.microsoft.playwright.options.Cookie c : cookies) {
                cookieMap.put(c.name, c.value);
            }
            logger.info("Cookies retrieved successfully.");

            CaesbSession session = new CaesbSession();
            session.setCookies(cookieMap);
            session.setViewState(viewState);
            session.setExecution(execution);
            logger.info("Login process completed successfully. Returning session object.");
            return session;
        } catch (Exception e) {
            logger.error("An error occurred during the login process: {}", e.getMessage(), e);
            throw e;
        }
    }
}