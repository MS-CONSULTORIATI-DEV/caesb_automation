package br.com.caesb.automation.dto;

import br.com.caesb.automation.config.CaesbSession;

/**
 * Wrapper que contém o resultado da baixa e a sessão (caso tenha sido renovada)
 */
public class BaixaComSessaoResultado {
    private final BaixaResultado resultado;
    private final CaesbSession sessao;
    private final boolean sessaoRenovada;
    
    public BaixaComSessaoResultado(BaixaResultado resultado, CaesbSession sessao, boolean sessaoRenovada) {
        this.resultado = resultado;
        this.sessao = sessao;
        this.sessaoRenovada = sessaoRenovada;
    }
    
    public BaixaResultado getResultado() {
        return resultado;
    }
    
    public CaesbSession getSessao() {
        return sessao;
    }
    
    public boolean isSessaoRenovada() {
        return sessaoRenovada;
    }
}

