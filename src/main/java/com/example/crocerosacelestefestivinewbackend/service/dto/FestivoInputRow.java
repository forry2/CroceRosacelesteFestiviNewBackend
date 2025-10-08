package com.example.crocerosacelestefestivinewbackend.service.dto;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

public class FestivoInputRow {
    public int excelRowNumber;
    public String note1; // col1
    public String note2; // col2
    public LocalDate date;
    public String turno; // MP | SN
    public int peso; // > 0
    public Optional<Integer> assegnazioneForzata; // 1..10
    public Set<Integer> squadreEscluse; // 1..10
    public String errorMessage; // Messaggio di errore per questo festivo (se presente)
}


