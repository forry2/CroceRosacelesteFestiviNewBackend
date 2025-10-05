package com.example.crocerosacelestefestivinewbackend.service;

import com.example.crocerosacelestefestivinewbackend.api.ValidationException;
import com.example.crocerosacelestefestivinewbackend.service.dto.FestivoInputRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

import static com.example.crocerosacelestefestivinewbackend.service.SchedulingCommon.*;

@Service
public class GreedySchedulerService {
    private static final Logger log = LoggerFactory.getLogger(GreedySchedulerService.class);

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
                                   int minProximityDays) {
        BuiltModel bm = buildUnits(rows, pesanti, start, end);
        log.info("[GREEDY] Units built: {}", bm.units.size());
        List<Map<String, Object>> violations = new ArrayList<>();

        // Sort: first MPB blocks by descending peso, then remaining by descending peso
        List<FestivoUnit> units = new ArrayList<>(bm.units);
        units.sort((a, b) -> {
            int ta = "MPB".equals(a.tipo) ? 0 : 1;
            int tb = "MPB".equals(b.tipo) ? 0 : 1;
            if (ta != tb) return Integer.compare(ta, tb);
            return Integer.compare(b.peso, a.peso);
        });
        log.debug("[GREEDY] Sorted units. First={} peso={}", units.isEmpty()?"-":units.get(0).id, units.isEmpty()?0:units.get(0).peso);

        Map<String, Integer> assignment = new HashMap<>();
        long[] pesi = new long[11]; // 1..10
        int[] eventiQuestoAnnoPesanti = new int[11];
        Map<Integer, int[]> eventiPerMese = new HashMap<>();
        Map<Integer, long[]> pesiPerMese = new HashMap<>();
        for (int s = 1; s <= 10; s++) { eventiPerMese.put(s, new int[12]); pesiPerMese.put(s, new long[12]); }

        // helper: team can take unit?
        for (FestivoUnit u : units) {
            if (log.isDebugEnabled()) {
                log.debug("[GREEDY] Unit start id={} tipo={} peso={} dates={} month={} forzata={} escluse={} pesante={}",
                        u.id, u.tipo, u.peso, u.dates, u.month, u.forzata.orElse(null), u.escluse, u.pesante);
            }
            Integer forced = u.forzata.orElse(null);
            List<Integer> candidates = new ArrayList<>();
            if (forced != null) {
                candidates.add(forced);
            } else {
                for (int team = 1; team <= 10; team++) candidates.add(team);
            }
            candidates.removeIf(u.escluse::contains);
            if (log.isTraceEnabled()) log.trace("[GREEDY] Candidates after exclusions {} -> {}", u.id, candidates);

            // Same-day MP vs SN must be different teams
            // We'll enforce when assigning by checking existing assignment for same date other tipo

            // Filter by proximity for each date in unit
            candidates.removeIf(team -> u.dates.stream().anyMatch(d -> !proximityOk(team, d, minProximityDays)));
            if (log.isTraceEnabled()) log.trace("[GREEDY] Candidates after proximity {} -> {}", u.id, candidates);

            // Filter by monthly 1-event per team
            candidates.removeIf(team -> eventiPerMese.get(team)[u.month - 1] >= 1);
            if (log.isTraceEnabled()) log.trace("[GREEDY] Candidates after monthly limit {} -> {}", u.id, candidates);

            // Filter by heavy 1/year per team if pesante
            if (u.pesante) {
                candidates.removeIf(team -> eventiQuestoAnnoPesanti[team] >= 1);
                if (log.isTraceEnabled()) log.trace("[GREEDY] Candidates after heavy limit {} -> {}", u.id, candidates);
            }

            // Same day MP/SN or other unit on same date must not conflict (assign later check)

            if (candidates.isEmpty()) {
                addV(violations, u.rows.get(0).excelRowNumber, "__assign__", "Nessuna squadra idonea per il festivo");
                log.warn("[GREEDY] No candidates for unit {}", u.id);
                continue;
            }

            // Choose least loaded by pesi, then by eventi; tie-break: farthest last assignment (omitted for brevity), then lower id
            candidates.sort((a, b) -> {
                int cmp = Long.compare(pesi[a], pesi[b]);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(sum(eventiPerMese.get(a)), sum(eventiPerMese.get(b)));
                if (cmp != 0) return cmp;
                return Integer.compare(a, b);
            });
            if (log.isTraceEnabled()) log.trace("[GREEDY] Candidates sorted {} -> {}", u.id, candidates);

            Integer chosen = null;
            // enforce same-day different teams: if same date has other assignment, ensure different team
            for (Integer team : candidates) {
                boolean conflict = false;
                for (LocalDate d : u.dates) {
                    String otherKeyMP = d + "|MP";
                    String otherKeySN = d + "|SN";
                    if ("MPB".equals(u.tipo)) {
                        // For each date in block MP, ensure SN (if exists) is not assigned to same team
                        Integer sn = assignment.get(otherKeySN);
                        if (sn != null && sn.equals(team)) { conflict = true; break; }
                    } else if ("SN".equals(u.tipo)) {
                        Integer mp = assignment.get(otherKeyMP);
                        if (mp != null && mp.equals(team)) { conflict = true; break; }
                    } else { // u.tipo == MP single
                        Integer sn = assignment.get(otherKeySN);
                        if (sn != null && sn.equals(team)) { conflict = true; break; }
                    }
                }
                if (!conflict) { chosen = team; break; }
            }

            if (chosen == null) {
                addV(violations, u.rows.get(0).excelRowNumber, "__assign__", "Conflitto con vincolo MP vs SN nello stesso giorno");
                log.warn("[GREEDY] Day conflict for unit {}", u.id);
                continue;
            }

            // assign
            if ("MPB".equals(u.tipo)) {
                assignment.put(u.dates.get(0) + "|MP", chosen);
                assignment.put(u.dates.get(1) + "|MP", chosen);
            } else {
                assignment.put(u.dates.get(0) + "|" + u.tipo, chosen);
            }
            if (log.isDebugEnabled()) {
                log.debug("[GREEDY] Assigned unit={} tipo={} to team={} peso={} dates={}", u.id, u.tipo, chosen, u.peso, u.dates);
            }

            pesi[chosen] += u.peso;
            eventiPerMese.get(chosen)[u.month - 1] += 1;
            if (u.pesante) eventiQuestoAnnoPesanti[chosen] += 1;
            pesiPerMese.get(chosen)[u.month - 1] += u.peso;
            if (log.isTraceEnabled()) {
                log.trace("[GREEDY] Team {} monthly events={} weights={} after unit {}", chosen,
                        Arrays.toString(eventiPerMese.get(chosen)), Arrays.toString(pesiPerMese.get(chosen)), u.id);
            }
        }

        if (!violations.isEmpty()) {
            log.warn("[GREEDY] Violations at end: {}", violations.size());
            throw new ValidationException(violations);
        }

        return new ScheduleResult(assignment, pesiPerMese, eventiPerMese, bm.mutatedRows);
    }

    private static int sum(int[] a) {
        int s = 0; for (int v : a) s += v; return s;
    }
}


