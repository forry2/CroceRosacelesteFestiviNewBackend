package com.example.crocerosacelestefestivinewbackend.service;

import com.example.crocerosacelestefestivinewbackend.api.ValidationException;
import com.example.crocerosacelestefestivinewbackend.service.dto.FestivoInputRow;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

import static com.example.crocerosacelestefestivinewbackend.service.SchedulingCommon.*;

@Service
public class MilpSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(MilpSchedulerService.class);

    public static class ScheduleResult {
        public final Map<String, Integer> assignment; // key=date|turno -> squadra
        public final Map<Integer, long[]> pesiPerMese;
        public final Map<Integer, int[]> eventiPerMese;
        public final List<FestivoInputRow> rowsMutated;
        public ScheduleResult(Map<String, Integer> assignment, Map<Integer, long[]> pesiPerMese, Map<Integer, int[]> eventiPerMese, List<FestivoInputRow> rowsMutated) {
            this.assignment = assignment;
            this.pesiPerMese = pesiPerMese;
            this.eventiPerMese = eventiPerMese;
            this.rowsMutated = rowsMutated;
        }
    }

    public ScheduleResult schedule(List<FestivoInputRow> rows,
                                   Set<String> pesanti,
                                   LocalDate start,
                                   LocalDate end,
                                   int minProximityDays,
                                   double alpha) {
        Loader.loadNativeLibraries();
        log.info("[MILP] Building model. rows={} heavy={} period=[{}..{}]", rows.size(), pesanti.size(), start, end);

        BuiltModel bm = buildUnits(rows, pesanti, start, end);
        if (log.isDebugEnabled()) log.debug("[MILP] Units built: {}", bm.units.size());

        // Pre-validate forzate monthly/yearly heavy conflicts
        List<Map<String, Object>> violations = new ArrayList<>();
        Map<String, Integer> forcedMonthCount = new HashMap<>(); // key team|year|month
        Map<String, Integer> forcedHeavyYear = new HashMap<>(); // key team|year
        for (FestivoUnit u : bm.units) {
            if (u.forzata.isPresent()) {
                String k = u.forzata.get() + "|" + u.year + "|" + u.month;
                forcedMonthCount.put(k, forcedMonthCount.getOrDefault(k, 0) + 1);
                if (u.pesante) {
                    String ky = u.forzata.get() + "|" + u.year;
                    forcedHeavyYear.put(ky, forcedHeavyYear.getOrDefault(ky, 0) + 1);
                }
            }
        }
        for (Map.Entry<String, Integer> e : forcedMonthCount.entrySet()) {
            if (e.getValue() > 1) addV(violations, 1, "assegnazione forzata", "Più forzate per stessa squadra nello stesso mese");
        }
        for (Map.Entry<String, Integer> e : forcedHeavyYear.entrySet()) {
            if (e.getValue() > 1) addV(violations, 1, "assegnazione forzata", "Più festivi pesanti forzati per stessa squadra nello stesso anno");
        }
        if (!violations.isEmpty()) throw new ValidationException(violations);

        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            throw new RuntimeException("Solver SCIP non disponibile");
        }

        int U = bm.units.size();
        int T = 10;
        MPVariable[][] x = new MPVariable[U][T + 1]; // 1..10
        for (int u = 0; u < U; u++) {
            for (int t = 1; t <= T; t++) {
                x[u][t] = solver.makeIntVar(0, 1, "x_u" + u + "_t" + t);
            }
        }
        if (log.isTraceEnabled()) log.trace("[MILP] Variables created: x={}, W/E per team, L and Emax", U * T);

        // per-team totals
        MPVariable[] W = new MPVariable[T + 1];
        MPVariable[] E = new MPVariable[T + 1];
        for (int t = 1; t <= T; t++) {
            W[t] = solver.makeIntVar(0, MPSolver.infinity(), "W_t" + t);
            E[t] = solver.makeIntVar(0, MPSolver.infinity(), "E_t" + t);
        }
        MPVariable L = solver.makeIntVar(0, MPSolver.infinity(), "L");
        MPVariable Emax = solver.makeIntVar(0, MPSolver.infinity(), "Emax");

        // Exactly one team per unit
        for (int u = 0; u < U; u++) {
            MPConstraint c = solver.makeConstraint(1, 1, "one_team_u" + u);
            for (int t = 1; t <= T; t++) c.setCoefficient(x[u][t], 1);
        }

        // Apply exclusions, forzate, proximity
        for (int u = 0; u < U; u++) {
            FestivoUnit fu = bm.units.get(u);
            for (int t = 1; t <= T; t++) {
                if (fu.escluse.contains(t)) {
                    MPConstraint c = solver.makeConstraint(0, 0, "excl_u" + u + "_t" + t);
                    c.setCoefficient(x[u][t], 1);
                }
                boolean proxOk = true;
                for (LocalDate d : fu.dates) {
                    if (!proximityOk(t, d, minProximityDays)) { proxOk = false; break; }
                }
                if (!proxOk) {
                    MPConstraint c = solver.makeConstraint(0, 0, "prox_u" + u + "_t" + t);
                    c.setCoefficient(x[u][t], 1);
                }
            }
            if (fu.forzata.isPresent()) {
                for (int t = 1; t <= T; t++) {
                    if (t == fu.forzata.get()) {
                        MPConstraint c = solver.makeConstraint(1, 1, "force_u" + u + "_t" + t);
                        c.setCoefficient(x[u][t], 1);
                    } else {
                        MPConstraint c = solver.makeConstraint(0, 0, "force0_u" + u + "_t" + t);
                        c.setCoefficient(x[u][t], 1);
                    }
                }
            }
            if (log.isTraceEnabled()) log.trace("[MILP] Constraints for unit {} set (exclusions/proximity/forzate)", u);
        }

        // Same-day MP vs SN different teams
        // Build map date -> MP unit index and SN unit index if present
        Map<LocalDate, Integer> mpAtDate = new HashMap<>();
        Map<LocalDate, Integer> snAtDate = new HashMap<>();
        for (int u = 0; u < U; u++) {
            FestivoUnit fu = bm.units.get(u);
            if ("MPB".equals(fu.tipo)) {
                mpAtDate.put(fu.dates.get(0), u);
                mpAtDate.put(fu.dates.get(1), u);
            } else if ("MP".equals(fu.tipo)) {
                mpAtDate.put(fu.dates.get(0), u);
            } else if ("SN".equals(fu.tipo)) {
                snAtDate.put(fu.dates.get(0), u);
            }
        }
        for (LocalDate d : mpAtDate.keySet()) {
            Integer um = mpAtDate.get(d);
            Integer us = snAtDate.get(d);
            if (us == null) continue;
            for (int t = 1; t <= T; t++) {
                MPConstraint c = solver.makeConstraint(0, 1, "daydiff_" + d + "_t" + t);
                c.setCoefficient(x[um][t], 1);
                c.setCoefficient(x[us][t], 1);
            }
        }

        // Monthly <=1 per team (by month within the period year(s))
        Map<String, List<Integer>> unitsByTeamMonthKey = new HashMap<>(); // key monthKey -> list of unit indices that belong to that monthKey
        for (int u = 0; u < U; u++) {
            FestivoUnit fu = bm.units.get(u);
            String monthKey = fu.year + "-" + String.format("%02d", fu.month);
            unitsByTeamMonthKey.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(u);
        }
        for (String mk : unitsByTeamMonthKey.keySet()) {
            List<Integer> idxs = unitsByTeamMonthKey.get(mk);
            for (int t = 1; t <= T; t++) {
                MPConstraint c = solver.makeConstraint(0, 1, "month1_" + mk + "_t" + t);
                for (Integer u : idxs) c.setCoefficient(x[u][t], 1);
            }
        }

        // Heavy per year <=1 per team
        Map<Integer, List<Integer>> heavyByYear = new HashMap<>();
        for (int u = 0; u < U; u++) {
            FestivoUnit fu = bm.units.get(u);
            if (fu.pesante) heavyByYear.computeIfAbsent(fu.year, k -> new ArrayList<>()).add(u);
        }
        for (Map.Entry<Integer, List<Integer>> e : heavyByYear.entrySet()) {
            for (int t = 1; t <= T; t++) {
                MPConstraint c = solver.makeConstraint(0, 1, "heavy1_y" + e.getKey() + "_t" + t);
                for (Integer u : e.getValue()) c.setCoefficient(x[u][t], 1);
            }
        }

        // Define W_t and E_t and L,Emax
        for (int t = 1; t <= T; t++) {
            MPConstraint cw = solver.makeConstraint(0, 0, "defW_t" + t);
            cw.setCoefficient(W[t], -1);
            for (int u = 0; u < U; u++) cw.setCoefficient(x[u][t], bm.units.get(u).peso);

            MPConstraint cL = solver.makeConstraint(0, MPSolver.infinity(), "capL_t" + t);
            cL.setCoefficient(L, 1);
            cL.setCoefficient(W[t], -1);

            MPConstraint ce = solver.makeConstraint(0, 0, "defE_t" + t);
            ce.setCoefficient(E[t], -1);
            for (int u = 0; u < U; u++) ce.setCoefficient(x[u][t], 1);

            MPConstraint ceMax = solver.makeConstraint(0, MPSolver.infinity(), "capEmax_t" + t);
            ceMax.setCoefficient(Emax, 1);
            ceMax.setCoefficient(E[t], -1);
        }

        // Objective: minimize L (primary) + small weight * Emax (secondary)
        MPObjective obj = solver.objective();
        // Normalizzazione: totale pesi ed eventi
        long totalPeso = 0; for (FestivoUnit fu : bm.units) totalPeso += fu.peso;
        int totalEventi = bm.units.size();
        double wL = (alpha <= 0) ? 0.0 : alpha / Math.max(1.0, (double) Math.max(1, totalPeso));
        double wE = (alpha >= 1) ? 0.0 : (1.0 - alpha) / Math.max(1.0, (double) Math.max(1, totalEventi));
        // Poiché l'objective non accetta divisioni direttamente sulle variabili, usiamo pesi scalati
        double scale = 1e6; // per evitare coefficienti troppo piccoli
        obj.setCoefficient(L, wL * scale);
        obj.setCoefficient(Emax, wE * scale);
        obj.setMinimization();

        long t0 = System.currentTimeMillis();
        MPSolver.ResultStatus status = solver.solve();
        long dt = System.currentTimeMillis() - t0;
        log.info("[MILP] Solve status={}, durationMs={}", status, dt);
        if (!(status == MPSolver.ResultStatus.OPTIMAL || status == MPSolver.ResultStatus.FEASIBLE)) {
            addV(violations, 0, "__assign__", "Impossibile assegnare tutti i festivi rispettando i vincoli");
            throw new ValidationException(violations);
        }

        Map<String, Integer> assignment = new HashMap<>();
        Map<Integer, long[]> pesiPerMese = new HashMap<>();
        Map<Integer, int[]> eventiPerMese = new HashMap<>();
        for (int t = 1; t <= T; t++) { pesiPerMese.put(t, new long[12]); eventiPerMese.put(t, new int[12]); }

        for (int u = 0; u < U; u++) {
            int chosen = -1;
            for (int t = 1; t <= T; t++) {
                if (x[u][t].solutionValue() > 0.5) { chosen = t; break; }
            }
            if (chosen == -1) {
                addV(violations, bm.units.get(u).rows.get(0).excelRowNumber, "__assign__", "Unità non assegnata");
                continue;
            }
            FestivoUnit fu = bm.units.get(u);
            if ("MPB".equals(fu.tipo)) {
                assignment.put(fu.dates.get(0) + "|MP", chosen);
                assignment.put(fu.dates.get(1) + "|MP", chosen);
            } else {
                assignment.put(fu.dates.get(0) + "|" + fu.tipo, chosen);
            }
            pesiPerMese.get(chosen)[fu.month - 1] += fu.peso;
            eventiPerMese.get(chosen)[fu.month - 1] += 1;
        }

        if (!violations.isEmpty()) throw new ValidationException(violations);

        return new ScheduleResult(assignment, pesiPerMese, eventiPerMese, bm.mutatedRows);
    }
}


