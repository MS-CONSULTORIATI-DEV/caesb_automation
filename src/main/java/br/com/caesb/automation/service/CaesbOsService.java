package br.com.caesb.automation.service;

import br.com.caesb.automation.config.CaesbSession;
import br.com.caesb.automation.util.OsParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Serviço responsável por consultar a aba <b>Recebidas</b> do GCOM e devolver
 * a lista de Ordens de Serviço pendentes.
 *
 * <p>O número de registros por página vem da propriedade
 * <code>caesb.os.rpp</code> definida em <code>application.properties</code>.
 * Caso a chave não exista, é usado o valor padrão 100.</p>
 */
@Service
public class CaesbOsService {

    private static final Logger log = LogManager.getLogger(CaesbOsService.class);

    private static final String BASE =
            "https://sistemas.caesb.df.gov.br/gcom/app/atendimento/os/controleOs/controle";

    /** Registros por página – configurável em <code>application.properties</code>. */
    @Value("${caesb.os.rpp:100}")
    private int registrosPorPagina;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /*==================================================================
     * PÚBLICO: devolve lista de OS pendentes (aba “Recebidas”)
     *================================================================== */
    public List<String> listarOs(CaesbSession session) throws IOException, InterruptedException {
        log.info("Iniciando processo para listar OS pendentes ({} por página).",
                registrosPorPagina);

        /* ------------------------------------------------------------
         * 1) GET inicial — só para obter JSF ViewState e execution
         * ------------------------------------------------------------ */
        URI primeiraUri = URI.create(BASE + "?id=1111");
        HttpRequest primeiroGet = HttpRequest.newBuilder()
                .uri(primeiraUri)
                .header("Cookie", cookies(session))
                .GET()
                .build();

        HttpResponse<String> r1 =
                client.send(primeiroGet, HttpResponse.BodyHandlers.ofString());

        log.debug("GET inicial => status {}, URI redirecionada: {}",
                r1.statusCode(), r1.uri());

        /* Captura “execution” (pode vir na própria URI ou no form) */
        String execution = getExecutionFromUri(r1.uri());
        if (execution == null) {
            execution = group(r1.body(),
                    "(?s)action=\"[^\"]*execution=([^\"]+)\"");
        }
        if (execution == null) {
            throw new IOException("Não foi possível determinar o parâmetro 'execution'.");
        }

        /* Captura javax.faces.ViewState */
        String viewState = group(r1.body(),
                "(?s)name=\"javax.faces.ViewState\"[^>]+value=\"([^\"]+)\"");
        if (viewState == null) {
            throw new IOException("javax.faces.ViewState não encontrado.");
        }

        /* ------------------------------------------------------------
         * 2) POST para ativar a aba RECEBIDAS
         * ------------------------------------------------------------ */
        Map<String, String> tabBody = new LinkedHashMap<>();
        tabBody.put("javax.faces.partial.ajax", "true");
        tabBody.put("javax.faces.source", "abas");
        tabBody.put("javax.faces.partial.execute", "abas");
        tabBody.put("javax.faces.partial.render", "formBotoes formPesquisa abas");
        tabBody.put("javax.faces.behavior.event", "tabChange");
        tabBody.put("javax.faces.partial.event", "tabChange");
        tabBody.put("abas_contentLoad", "true");
        tabBody.put("abas_newTab", "abas:recebidas");
        tabBody.put("abas_tabindex", "0");
        tabBody.put("j_idt52", "j_idt52");
        tabBody.put("j_idt52:filtroRapidoInscricao", "");
        tabBody.put("javax.faces.ViewState", viewState);

        HttpRequest ativaRecebidas = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "?execution=" + execution))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Faces-Request", "partial/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", cookies(session))
                .POST(HttpRequest.BodyPublishers.ofString(serialize(tabBody)))
                .build();

        client.send(ativaRecebidas, HttpResponse.BodyHandlers.discarding());
        log.debug("Aba 'Recebidas' ativada.");

        /* ------------------------------------------------------------
         * 3) POST para alterar RPP (records-per-page)
         * ------------------------------------------------------------ */
        Map<String, String> rppBody = new LinkedHashMap<>();
        rppBody.put("javax.faces.partial.ajax", "true");
        rppBody.put("javax.faces.source", "abas:formRecebidas:tblRecebidas");
        rppBody.put("javax.faces.partial.execute", "abas:formRecebidas:tblRecebidas");
        rppBody.put("javax.faces.partial.render", "abas:formRecebidas:tblRecebidas");
        rppBody.put("abas:formRecebidas:tblRecebidas", "abas:formRecebidas:tblRecebidas");
        rppBody.put("abas:formRecebidas:tblRecebidas_pagination", "true");
        rppBody.put("abas:formRecebidas:tblRecebidas_first", "0");

        rppBody.put("abas:formRecebidas:tblRecebidas_rows",
                String.valueOf(registrosPorPagina));

        rppBody.put("abas:formRecebidas:tblRecebidas_skipChildren", "true");
        rppBody.put("abas:formRecebidas:tblRecebidas_encodeFeature", "true");
        rppBody.put("abas:formRecebidas", "abas:formRecebidas");
        rppBody.put("abas:formRecebidas:tblRecebidas_rppDD",
                String.valueOf(registrosPorPagina));
        rppBody.put("abas:formRecebidas:tblRecebidas_selection", "");
        rppBody.put("javax.faces.ViewState", viewState);

        HttpRequest atualizaRpp = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "?execution=" + execution))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Faces-Request", "partial/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", cookies(session))
                .POST(HttpRequest.BodyPublishers.ofString(serialize(rppBody)))
                .build();

        HttpResponse<String> rppResp =
                client.send(atualizaRpp, HttpResponse.BodyHandlers.ofString());

        /* ------------------------------------------------------------
         * 4) Faz o parsing dos números de OS
         * ------------------------------------------------------------ */
        List<String> os = OsParser.extrairNumerosOs(rppResp.body());

        log.info("Encontradas {} OS pendentes: {}", os.size(), os);
        return os;
    }


    /*==================================================================
     * MÉTODOS AUXILIARES
     *================================================================== */

    /** Concatena cookies no formato do cabeçalho HTTP. */
    private static String cookies(CaesbSession s) {
        return s.getCookies().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    /** Serializa um map application/x-www-form-urlencoded. */
    private static String serialize(Map<String, String> map) {
        return map.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Retorna o primeiro grupo de uma expressão regular ou {@code null}. */
    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Extrai <code>execution</code> do query-string da URI (se existir). */
    private static String getExecutionFromUri(URI uri) {
        if (uri == null) return null;
        String q = uri.getQuery();
        if (q == null) return null;
        int p = q.indexOf("execution=");
        return p >= 0 ? q.substring(p + 10) : null;
    }
}
