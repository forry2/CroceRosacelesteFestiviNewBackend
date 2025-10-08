package com.example.crocerosacelestefestivinewbackend.service;

import com.example.crocerosacelestefestivinewbackend.api.ValidationException;
import com.example.crocerosacelestefestivinewbackend.service.dto.FestivoInputRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ExcelParsingService {

    private static final DateTimeFormatter STRICT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static class ParseResult {
        public final List<FestivoInputRow> rows;
        public final Set<String> pesanti; // key = date+"|"+turno for sheet "festivi-pesanti"
        public ParseResult(List<FestivoInputRow> rows, Set<String> pesanti) {
            this.rows = rows;
            this.pesanti = pesanti;
        }
    }

    public ParseResult parse(InputStream inputStream, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> violations = new ArrayList<>();
        List<FestivoInputRow> rows = new ArrayList<>();
        Set<String> duoKey = new HashSet<>();

        try (Workbook wb = new XSSFWorkbook(inputStream)) {
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExcelParsingService.class);
            log.info("[PARSE] Start parsing Excel. period=[{}..{}]", startDate, endDate);
            // Sheet 1: lista-festivi
            Sheet s = wb.getSheet("lista-festivi");
            if (s == null) {
                ValidationUtil.addV(violations, 1, "__sheet__", "Foglio 'lista-festivi' mancante");
                throw new ValidationException(violations);
            }
            Row header = s.getRow(0);
            if (header == null) {
                ValidationUtil.addV(violations, 1, "__header__", "Header mancante in riga 1");
                throw new ValidationException(violations);
            }
            // Expect columns: col1/2 notes, col3=data, col4=turno, col5=peso, col6=assegnazione forzata, col7=squadre escluse
            if (!"data".equals(getStringCell(header, 2)) ||
                !"turno".equals(getStringCell(header, 3)) ||
                !"peso".equals(getStringCell(header, 4)) ||
                !"assegnazione forzata".equals(getStringCell(header, 5)) ||
                !"squadre escluse".equals(getStringCell(header, 6))) {
                ValidationUtil.addV(violations, 1, "__header__", "Header non valido (atteso: data, turno, peso, assegnazione forzata, squadre escluse in col 3..7)");
                throw new ValidationException(violations);
            }

            // Collect to map by date for further checks
            Map<String, FestivoInputRow> byKey = new HashMap<>();
            Map<LocalDate, Set<String>> byDateTurni = new HashMap<>();

            for (int r = 1; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                if (row == null) {
                    ValidationUtil.addV(violations, r + 1, "__row__", "Riga vuota");
                    continue;
                }
                String note1 = getTrimmed(row, 0);
                String note2 = getTrimmed(row, 1);
                String dataStr = getTrimmed(row, 2);
                String turno = getTrimmed(row, 3);
                String pesoStr = getTrimmed(row, 4);
                String forzataStr = getTrimmed(row, 5);
                String escluseStr = getTrimmed(row, 6);

                if (isAllEmpty(note1, note2, dataStr, turno, pesoStr, forzataStr, escluseStr)) {
                    ValidationUtil.addV(violations, r + 1, "__row__", "Riga vuota");
                    continue;
                }

                // data
                LocalDate date = null;
                try {
                    date = LocalDate.parse(dataStr, STRICT_FMT);
                } catch (DateTimeParseException e) {
                    ValidationUtil.addV(violations, r + 1, "data", "Formato data non valido, atteso YYYY-MM-DD");
                }
                if (date != null) {
                    if (date.isBefore(startDate) || date.isAfter(endDate)) {
                        ValidationUtil.addV(violations, r + 1, "data", "Data fuori dal periodo specificato");
                    }
                }

                // turno
                if (!("MP".equals(turno) || "SN".equals(turno))) {
                    ValidationUtil.addV(violations, r + 1, "turno", "Valore non valido (atteso MP o SN, maiuscolo)");
                }

                // peso
                Integer peso = null;
                try {
                    peso = Integer.valueOf(pesoStr);
                    if (peso <= 0) {
                        ValidationUtil.addV(violations, r + 1, "peso", "Deve essere un intero > 0");
                    }
                } catch (Exception e) {
                    ValidationUtil.addV(violations, r + 1, "peso", "Campo obbligatorio, intero > 0");
                }

                // assegnazione forzata
                Optional<Integer> forzata = Optional.empty();
                if (forzataStr != null && !forzataStr.isEmpty()) {
                    try {
                        int f = Integer.parseInt(forzataStr.trim());
                        if (f < 1 || f > 10) {
                            ValidationUtil.addV(violations, r + 1, "assegnazione forzata", "Valore fuori range 1..10");
                        } else {
                            forzata = Optional.of(f);
                        }
                    } catch (NumberFormatException nfe) {
                        ValidationUtil.addV(violations, r + 1, "assegnazione forzata", "Deve essere un intero 1..10");
                    }
                }

                // squadre escluse: punto e virgola come separatore, spazi tollerati
                Set<Integer> escluse = new LinkedHashSet<>();
                if (escluseStr != null && !escluseStr.isEmpty()) {
                    String[] parts = escluseStr.split(";");
                    for (String p : parts) {
                        String t = p.trim();
                        if (t.isEmpty()) continue;
                        try {
                            int n = Integer.parseInt(t);
                            if (n < 1 || n > 10) {
                                ValidationUtil.addV(violations, r + 1, "squadre escluse", "Valore fuori range 1..10: " + t);
                            } else {
                                escluse.add(n);
                            }
                        } catch (NumberFormatException nfe) {
                            ValidationUtil.addV(violations, r + 1, "squadre escluse", "Valore non numerico: " + t);
                        }
                    }
                    if (escluse.size() == 10) {
                        ValidationUtil.addV(violations, r + 1, "squadre escluse", "Tutte le 10 squadre escluse non è ammesso");
                    }
                }

                // duplicati data+turno
                if (date != null && ("MP".equals(turno) || "SN".equals(turno))) {
                    String key = date + "|" + turno;
                    if (!duoKey.add(key)) {
                        ValidationUtil.addV(violations, r + 1, "__row__", "Duplicato data+turno");
                    }
                }

                // assemble if fields valid enough
                if (date != null && ("MP".equals(turno) || "SN".equals(turno)) && peso != null) {
                    FestivoInputRow ir = new FestivoInputRow();
                    ir.excelRowNumber = r + 1;
                    ir.note1 = note1;
                    ir.note2 = note2;
                    ir.date = date;
                    ir.turno = turno;
                    ir.peso = peso;
                    ir.assegnazioneForzata = forzata;
                    ir.squadreEscluse = escluse;
                    rows.add(ir);
                    if (rows.size() % 100 == 0) log.debug("[PARSE] rows parsed={}", rows.size());
                    byKey.put(date + "|" + turno, ir);
                    byDateTurni.computeIfAbsent(date, k -> new HashSet<>()).add(turno);
                    if (forzata.isPresent() && escluse.contains(forzata.get())) {
                        ValidationUtil.addV(violations, r + 1, "assegnazione forzata", "Conflitto: squadra forzata presente tra le escluse");
                    }
                }
            }

            // Sheet 2: festivi-pesanti (opzionale)
            Set<String> pesanti = new HashSet<>();
            Sheet heavy = wb.getSheet("festivi-pesanti");
            if (heavy != null) {
                Row h = heavy.getRow(0);
                if (h == null || !"data".equals(getStringCell(h, 0)) || !"turno".equals(getStringCell(h, 1))) {
                    ValidationUtil.addV(violations, 1, "__header__", "Header festivi-pesanti non valido (atteso: data, turno)");
                } else {
                    for (int r = 1; r <= heavy.getLastRowNum(); r++) {
                        Row row = heavy.getRow(r);
                        if (row == null) continue;
                        String dataStr = getTrimmed(row, 0);
                        String turno = getTrimmed(row, 1);
                        try {
                            LocalDate d = LocalDate.parse(dataStr, STRICT_FMT);
                            if (d.isBefore(startDate) || d.isAfter(endDate)) {
                                ValidationUtil.addV(violations, r + 1, "data", "Data fuori periodo");
                            }
                            if (!"MP".equals(turno) && !"SN".equals(turno)) {
                                ValidationUtil.addV(violations, r + 1, "turno", "Valore non valido (MP|SN)");
                            } else {
                                pesanti.add(d + "|" + turno);
                            }
                        } catch (Exception e) {
                            ValidationUtil.addV(violations, r + 1, "data", "Formato data non valido in festivi-pesanti");
                        }
                    }
                    log.debug("[PARSE] heavy marked count={}", pesanti.size());
                }
            }

            // Validazione blocco sabato/domenica MP: se una delle due righe manca (ed entrambe le date sono nel periodo) → errore
            LocalDate cursor = startDate;
            while (!cursor.isAfter(endDate)) {
                if (cursor.getDayOfWeek() == java.time.DayOfWeek.SATURDAY) {
                    LocalDate sunday = cursor.plusDays(1);
                    if (!sunday.isAfter(endDate)) { // domenica nel periodo
                        boolean hasSatMP = byKey.containsKey(cursor + "|MP");
                        boolean hasSunMP = byKey.containsKey(sunday + "|MP");
                        if (hasSatMP ^ hasSunMP) {
                            FestivoInputRow presentRow = hasSatMP ? byKey.get(cursor + "|MP") : byKey.get(sunday + "|MP");
                            LocalDate missingDate = hasSatMP ? sunday : cursor;
                            String msg = "Coppia sab-dom MP incompleta: presente MP per " + presentRow.date + ", manca MP per " + missingDate;
                            ValidationUtil.addV(violations, presentRow != null ? presentRow.excelRowNumber : 1, "turno", msg);
                        }
                    }
                }
                cursor = cursor.plusDays(1);
            }

            // Validazione 31 per mesi con 31 nel periodo
            validate31(rows, startDate, endDate, violations);

            if (!violations.isEmpty()) {
                log.warn("[PARSE] Validation violations found: {}", violations.size());
                throw new ValidationException(violations);
            }
            log.info("[PARSE] Completed. rows={}, heavy={}", rows.size(), pesanti.size());
            return new ParseResult(rows, pesanti);
        } catch (ValidationException ve) {
            throw ve;
        } catch (Exception e) {
            ValidationUtil.addV(violations, 0, "__global__", e.getMessage());
            throw new ValidationException(violations);
        }
    }

    private static void validate31(List<FestivoInputRow> rows, LocalDate start, LocalDate end, List<Map<String, Object>> v) {
        Map<String, Set<String>> byDate = new HashMap<>();
        for (FestivoInputRow r : rows) {
            if (r.date.getDayOfMonth() == 31) {
                byDate.computeIfAbsent(r.date.toString(), k -> new HashSet<>()).add(r.turno);
            }
        }
        LocalDate cur = start.withDayOfMonth(1);
        while (!cur.isAfter(end)) {
            int length = cur.lengthOfMonth();
            if (length == 31) {
                LocalDate d31 = cur.withDayOfMonth(31);
                if (!d31.isBefore(start) && !d31.isAfter(end)) {
                    Set<String> set = byDate.getOrDefault(d31.toString(), Collections.emptySet());
                    // SN must be present
                    if (!set.contains("SN")) {
                        ValidationUtil.addV(v, 1, "data", "Il 31 del mese " + d31.getMonth() + " deve contenere SN");
                    }
                    // MP allowed only if 31 is Saturday or Sunday; otherwise MP is invalid
                    if (set.contains("MP")) {
                        java.time.DayOfWeek dow = d31.getDayOfWeek();
                        boolean weekend = dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY;
                        if (!weekend) {
                            ValidationUtil.addV(v, 1, "turno", "Il 31 infrasettimanale non può avere MP");
                        }
                    }
                }
            }
            cur = cur.plusMonths(1);
        }
    }

    private static boolean isAllEmpty(String... s) {
        for (String x : s) if (x != null && !x.isEmpty()) return false;
        return true;
    }

    private static String getTrimmed(Row r, int c) {
        Cell cell = r.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        cell.setCellType(CellType.STRING);
        String v = cell.getStringCellValue();
        return v == null ? "" : v.trim();
    }

    private static String getStringCell(Row r, int c) {
        Cell cell = r.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        cell.setCellType(CellType.STRING);
        return cell.getStringCellValue();
    }
}


