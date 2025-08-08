package br.com.caesb.automation.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai números de OS da aba “Recebidas”
 * em qualquer uma das três formas abaixo:
 *   1) resposta PrimeFaces (<partial-response>);
 *   2) fragmento HTML bruto com &lt;tr&gt; intactos;
 *   3) HTML já “sanitizado” pelo Jsoup (sem &lt;tr&gt;).
 */
public class OsParser {

    /* ------------------------- API pública ------------------------- */

    public static List<String> extrairNumerosOs(String texto) {
        if (texto.contains("<partial-response")) {
            return extrairDeRespostaPrimefaces(texto);
        }
        return extrairDeHtmlFragmento(texto);
    }

    /* ------------------- 1. Resposta PrimeFaces -------------------- */

    private static List<String> extrairDeRespostaPrimefaces(String xml) {
        Pattern p = Pattern.compile(
                "<update[^>]*id=\"abas:formRecebidas:tblRecebidas\"[^>]*>\\s*<!\\[CDATA\\[(.*?)]]>",
                Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return extrairDeHtmlFragmento(m.group(1));
        }
        return List.of();
    }

    /* ------------------ 2. Fragmento apenas HTML ------------------ */

    private static List<String> extrairDeHtmlFragmento(String htmlFragment) {

        /* ---------- 1ª tentativa: ainda existem <tr>? ---------- */
        if (htmlFragment.contains("<tr")) {
            String wrapped = "<table>" + htmlFragment + "</table>";
            Document doc   = Jsoup.parse(wrapped);

            Set<String> encontrados = new LinkedHashSet<>();
            Elements linhas         = doc.select("tr[data-ri]"); // de 0 a n

            for (Element linha : linhas) {
                Elements tds = linha.select("td");
                if (tds.size() > 3) {
                    String num = tds.get(3).text().trim();
                    if (!num.isBlank()) {
                        encontrados.add(num);
                    }
                }
            }
            if (!encontrados.isEmpty()) {
                return new ArrayList<>(encontrados);
            }
            // se chegou aqui, não achou nada — cai no fallback
        }

        /* --------- 2ª tentativa: sem <tr>, usar regex --------- */
        // números de OS têm sempre 16-18 dígitos e começam por 10… ou 107…
        Pattern numeroOs = Pattern.compile("\\b\\d{16,18}\\b");
        Matcher m        = numeroOs.matcher(htmlFragment);

        Set<String> encontrados = new LinkedHashSet<>();
        while (m.find()) {
            encontrados.add(m.group());
        }
        return new ArrayList<>(encontrados);
    }

    /* ---------------------- Pequeno teste CLI --------------------- */

    public static void main(String[] args) {
        String caminho = "C:\\Users\\User\\Downloads\\teste.txt";

        try {
            String conteudo = Files.readString(Path.of(caminho));
            List<String> os = extrairNumerosOs(conteudo);

            if (os.isEmpty()) {
                System.out.println("Nenhuma OS encontrada.");
            } else {
                System.out.println("OS encontradas (" + os.size() + "):");
                os.forEach(System.out::println);
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler o arquivo: " + caminho);
            e.printStackTrace();
        }
    }
}
