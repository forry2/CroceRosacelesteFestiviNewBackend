package com.example.crocerosacelestefestivinewbackend.service;

import com.example.crocerosacelestefestivinewbackend.api.ValidationException;
import com.example.crocerosacelestefestivinewbackend.service.dto.FestivoInputRow;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

class SchedulingCommon {

    static class FestivoUnit {
        String id; // unique key
        List<FestivoInputRow> rows; // one or two
        List<LocalDate> dates; // one or two (for block MP)
        String tipo; // "MP","SN","MPB"
        int peso;
        int month; // for event counting (MPB -> month of saturday)
        int year;  // for heavy constraint (MPB -> year of saturday)
        boolean pesante;
        Optional<Integer> forzata; // unified
        Set<Integer> escluse; // union
    }

    static class BuiltModel {
        List<FestivoUnit> units;
        Map<LocalDate, String> pairTypeByDate; // date -> "MP" or "SN" markers present (for same-day constraint)
        Map<String, FestivoUnit> byKey;
        List<FestivoInputRow> mutatedRows; // with propagated forzata for MPB if needed
    }

    static BuiltModel buildUnits(List<FestivoInputRow> rows, Set<String> pesanti, LocalDate start, LocalDate end) {
        List<Map<String, Object>> violations = new ArrayList<>();
        Map<String, FestivoInputRow> byKey = new HashMap<>();
        Map<LocalDate, Set<String>> byDateTurni = new HashMap<>();
        for (FestivoInputRow r : rows) {
            byKey.put(r.date + "|" + r.turno, r);
            byDateTurni.computeIfAbsent(r.date, k -> new HashSet<>()).add(r.turno);
        }

        List<FestivoUnit> units = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<FestivoInputRow> mutated = new ArrayList<>();
        for (FestivoInputRow r : rows) {
            mutated.add(r);
        }

        // Build MP Saturday/Sunday blocks first
        for (FestivoInputRow r : rows) {
            if (!"MP".equals(r.turno)) continue;
            if (r.date.getDayOfWeek() != DayOfWeek.SATURDAY) continue;
            LocalDate sat = r.date;
            LocalDate sun = sat.plusDays(1);
            if (sun.isAfter(end)) continue; // no coupling outside period
            FestivoInputRow rSun = byKey.get(sun + "|MP");
            if (rSun == null) continue; // already validated, but ignore here

            String kSat = sat + "|MP";
            String kSun = sun + "|MP";
            if (used.contains(kSat) || used.contains(kSun)) continue;

            // Validate forzate coherence
            if (r.assegnazioneForzata.isPresent() && rSun.assegnazioneForzata.isPresent()
                    && !r.assegnazioneForzata.get().equals(rSun.assegnazioneForzata.get())) {
                addV(violations, r.excelRowNumber, "assegnazione forzata", "Forzate diverse tra sabato e domenica MP adiacenti");
            }
            // Unified forzata (propagate if one side only)
            Optional<Integer> forz = r.assegnazioneForzata.isPresent() ? r.assegnazioneForzata : rSun.assegnazioneForzata;
            if (forz.isPresent()) {
                // update mutated rows to reflect propagation
                if (r.assegnazioneForzata.isEmpty()) r.assegnazioneForzata = forz;
                if (rSun.assegnazioneForzata.isEmpty()) rSun.assegnazioneForzata = forz;
            }

            FestivoUnit u = new FestivoUnit();
            u.id = sat + ".." + sun + "|MPB";
            u.rows = Arrays.asList(r, rSun);
            u.dates = Arrays.asList(sat, sun);
            u.tipo = "MPB";
            u.peso = r.peso + rSun.peso;
            u.month = sat.getMonthValue();
            u.year = sat.getYear();
            u.pesante = pesanti.contains(sat + "|MP") || pesanti.contains(sun + "|MP");
            u.forzata = forz;
            u.escluse = new HashSet<>();
            u.escluse.addAll(r.squadreEscluse);
            u.escluse.addAll(rSun.squadreEscluse);
            if (forz.isPresent() && u.escluse.contains(forz.get())) {
                addV(violations, r.excelRowNumber, "assegnazione forzata", "Forzata in conflitto con esclusioni nel blocco sab-dom");
            }
            units.add(u);
            used.add(kSat); used.add(kSun);
        }

        // Remaining MP (non-block) and SN
        for (FestivoInputRow r : rows) {
            String k = r.date + "|" + r.turno;
            if (used.contains(k)) continue;
            FestivoUnit u = new FestivoUnit();
            u.id = k;
            u.rows = Collections.singletonList(r);
            u.dates = Collections.singletonList(r.date);
            u.tipo = r.turno;
            u.peso = r.peso;
            u.month = r.date.getMonthValue();
            u.year = r.date.getYear();
            u.pesante = pesanti.contains(k);
            u.forzata = r.assegnazioneForzata;
            u.escluse = new HashSet<>(r.squadreEscluse);
            units.add(u);
        }

        if (!violations.isEmpty()) throw new ValidationException(violations);

        // Map date -> present types for same-day constraint
        Map<LocalDate, String> pairTypeByDate = new HashMap<>();
        for (FestivoUnit u : units) {
            if ("MPB".equals(u.tipo)) {
                // mark both dates as having MP
                pairTypeByDate.put(u.dates.get(0), pairTypeByDate.getOrDefault(u.dates.get(0), "") + "MP");
                pairTypeByDate.put(u.dates.get(1), pairTypeByDate.getOrDefault(u.dates.get(1), "") + "MP");
            } else {
                pairTypeByDate.put(u.dates.get(0), pairTypeByDate.getOrDefault(u.dates.get(0), "") + u.tipo);
            }
        }

        BuiltModel bm = new BuiltModel();
        bm.units = units;
        bm.pairTypeByDate = pairTypeByDate;
        bm.byKey = null;
        bm.mutatedRows = mutated;
        return bm;
    }

    static boolean proximityOk(int team, LocalDate date, int minProxDays) {
        // team regular days: team 1 => 1,11,21 ; ... team 10 => 10,20,30 ; day 31 no regular
        for (int delta = -minProxDays; delta <= minProxDays; delta++) {
            LocalDate d = date.plusDays(delta);
            int dom = d.getDayOfMonth();
            if (dom == 31) continue; // 31 has no regular
            int mod = dom % 10; // 0..9; team 10 maps to 0, others to 1..9
            int teamOfDay = (mod == 0) ? 10 : mod;
            if (teamOfDay == team) {
                if (Math.abs(ChronoUnit.DAYS.between(date, d)) < minProxDays) return false; // strict < X forbidden, ==X allowed
            }
        }
        return true;
    }

    static void addV(List<Map<String, Object>> violations, int row, String field, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("row", row);
        m.put("field", field);
        m.put("message", message);
        violations.add(m);
    }
}


