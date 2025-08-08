package br.com.caesb.automation.controller;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controla a execução do processo de baixa.
 * Pode ser usado para iniciar/parar de forma segura em ambiente multithread.
 */
@Component
public class BaixaControladorExecucao {

    private final AtomicBoolean executando = new AtomicBoolean(false);
    private LocalDateTime inicio;
    private volatile String osAtual;
    /** Inicia o controle de execução. */
    public void iniciar() {
        executando.set(true);
        inicio = LocalDateTime.now();
    }

    /** Solicita a parada do processo. */
    public void parar() {
        executando.set(false);
    }

    /** Indica se deve continuar executando. */
    public boolean isExecutar() {
        return executando.get();
    }

    /** Indica se está atualmente em execução. */
    public boolean isExecutando() {
        return executando.get();
    }

    /** Retorna o horário em que a execução começou. */
    public LocalDateTime getInicio() {
        return inicio;
    }

    public AtomicBoolean getExecutando() {
        return executando;
    }

    public void setInicio(LocalDateTime inicio) {
        this.inicio = inicio;
    }

    public String getOsAtual() {
        return osAtual;
    }

    public void setOsAtual(String osAtual) {
        this.osAtual = osAtual;
    }
}
