package com.example.crocerosacelestefestivinewbackend.service;

import com.example.crocerosacelestefestivinewbackend.service.dto.FestivoInputRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Month;
import java.util.*;

@Service
public class ExcelOutputService {

    public byte[] buildOutput(List<FestivoInputRow> inputRows,
                              Map<String, Integer> assignment,
                              Map<Integer, long[]> pesiPerMese,
                              Map<Integer, int[]> eventiPerMese) {
        try (Workbook wb = new XSSFWorkbook()) {
            // Sheet 1: lista-festivi (preserva col1-col7, aggiungi col8 e col9)
            Sheet s = wb.createSheet("lista-festivi");
            Row header = s.createRow(0);
            header.createCell(0).setCellValue("");
            header.createCell(1).setCellValue("");
            header.createCell(2).setCellValue("data");
            header.createCell(3).setCellValue("turno");
            header.createCell(4).setCellValue("peso");
            header.createCell(5).setCellValue("assegnazione forzata");
            header.createCell(6).setCellValue("squadre escluse");
            header.createCell(7).setCellValue("squadra assegnata");
            header.createCell(8).setCellValue("note / errori");

            int r = 1;
            for (FestivoInputRow row : inputRows) {
                Row rr = s.createRow(r++);
                rr.createCell(0).setCellValue(row.note1 == null ? "" : row.note1);
                rr.createCell(1).setCellValue(row.note2 == null ? "" : row.note2);
                rr.createCell(2).setCellValue(row.date.toString());
                rr.createCell(3).setCellValue(row.turno);
                rr.createCell(4).setCellValue(row.peso);
                rr.createCell(5).setCellValue(row.assegnazioneForzata.map(Object::toString).orElse(""));
                rr.createCell(6).setCellValue(joinExcl(row.squadreEscluse));
                Integer squad = assignment.get(row.date + "|" + row.turno);
                rr.createCell(7).setCellValue(squad == null ? "" : String.valueOf(squad));
                rr.createCell(8).setCellValue(row.errorMessage == null ? "" : row.errorMessage);
            }

            // Sheet 2: riepilogo-pesi
            Sheet rp = wb.createSheet("riepilogo-pesi");
            Row hp = rp.createRow(0);
            hp.createCell(0).setCellValue("squadra");
            int c = 1;
            for (Month m : Month.values()) { hp.createCell(c++).setCellValue(shortMonth(m)); }
            hp.createCell(c).setCellValue("Totale");
            for (int squadra = 1; squadra <= 10; squadra++) {
                long[] arr = pesiPerMese.getOrDefault(squadra, new long[12]);
                Row row = rp.createRow(squadra);
                row.createCell(0).setCellValue(squadra);
                long tot = 0;
                for (int i = 0; i < 12; i++) { row.createCell(i + 1).setCellValue(arr[i]); tot += arr[i]; }
                row.createCell(13).setCellValue(tot);
            }

            // Sheet 3: riepilogo-eventi
            Sheet re = wb.createSheet("riepilogo-eventi");
            Row he = re.createRow(0);
            he.createCell(0).setCellValue("squadra");
            c = 1;
            for (Month m : Month.values()) { he.createCell(c++).setCellValue(shortMonth(m)); }
            he.createCell(c).setCellValue("Totale");
            for (int squadra = 1; squadra <= 10; squadra++) {
                int[] arr = eventiPerMese.getOrDefault(squadra, new int[12]);
                Row row = re.createRow(squadra);
                row.createCell(0).setCellValue(squadra);
                int tot = 0;
                for (int i = 0; i < 12; i++) { row.createCell(i + 1).setCellValue(arr[i]); tot += arr[i]; }
                row.createCell(13).setCellValue(tot);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String joinExcl(Set<Integer> excl) {
        if (excl == null || excl.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer i : excl) { if (!first) sb.append(";"); sb.append(i); first = false; }
        return sb.toString();
    }

    private static final String[] IT_MONTHS = {"Gen","Feb","Mar","Apr","Mag","Giu","Lug","Ago","Set","Ott","Nov","Dic"};
    private static String shortMonth(Month m) { return IT_MONTHS[m.getValue() - 1]; }
}


