
package br.com.caesb.automation.config;

import java.util.Map;

public class CaesbSession {
    private Map<String, String> cookies;
    private String execution;
    private String viewState;

    public Map<String, String> getCookies() { return cookies; }
    public void setCookies(Map<String, String> cookies) { this.cookies = cookies; }

    public String getExecution() { return execution; }
    public void setExecution(String execution) { this.execution = execution; }

    public String getViewState() { return viewState; }
    public void setViewState(String viewState) { this.viewState = viewState; }
}
