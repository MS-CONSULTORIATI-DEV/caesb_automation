package br.com.caesb.automation.dto;

import java.util.List;

public record BaixaResultado(
        String os,
        boolean sucesso,
        List<String> mensagens) {}