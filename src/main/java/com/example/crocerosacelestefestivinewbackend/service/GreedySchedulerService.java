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
                                   int minProximityDays,
                                   double alpha) {
        BuiltModel bm = buildUnits(rows, pesanti, start, end);
        log.info("[GREEDY] Units built: {}", bm.units.size());
        List<Map<String, Object>> violations = new ArrayList<>();

        // Sort: 1) forced assignments first, 2) then MPB blocks, 3) then by descending peso
        List<FestivoUnit> units = new ArrayList<>(bm.units);
        units.sort((a, b) -> {
            // Priority 1: forced assignments come first
            boolean af = a.forzata.isPresent();
            boolean bf = b.forzata.isPresent();
            if (af != bf) return af ? -1 : 1;
            
            // Priority 2: MPB blocks
            int ta = "MPB".equals(a.tipo) ? 0 : 1;
            int tb = "MPB".equals(b.tipo) ? 0 : 1;
            if (ta != tb) return Integer.compare(ta, tb);
            
            // Priority 3: descending peso
            return Integer.compare(b.peso, a.peso);
        });
        log.debug("[GREEDY] Sorted units. First={} peso={} forced={}", 
            units.isEmpty()?"-":units.get(0).id, 
            units.isEmpty()?0:units.get(0).peso,
            units.isEmpty()?false:units.get(0).forzata.isPresent());

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
                String reason = buildNoCandidatesReason(u, bm, minProximityDays, eventiPerMese, eventiQuestoAnnoPesanti);
                addV(violations, u.rows.get(0).excelRowNumber, "__assign__", reason);
                log.warn("[GREEDY] No candidates for unit {}. Reason: {}", u.id, reason);
                // Popola errorMessage nelle righe corrispondenti
                for (FestivoInputRow row : u.rows) {
                    row.errorMessage = reason;
                }
                continue;
            }

            // Choose least loaded by pesi, then by eventi; tie-break: farthest last assignment (omitted for brevity), then lower id
            // Score pesato: alpha * L' + (1-alpha) * Emax'
            candidates.sort((a, b) -> {
                double sa = scoreAfterAssign(bm, assignment, pesi, eventiPerMese, a, u, alpha);
                double sb = scoreAfterAssign(bm, assignment, pesi, eventiPerMese, b, u, alpha);
                int cmp = Double.compare(sa, sb);
                if (cmp != 0) return cmp;
                // tie-break legacy
                int cmpW = Long.compare(pesi[a], pesi[b]);
                if (cmpW != 0) return cmpW;
                cmpW = Integer.compare(sum(eventiPerMese.get(a)), sum(eventiPerMese.get(b)));
                if (cmpW != 0) return cmpW;
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
                String reason = "Conflitto con vincolo MP vs SN nello stesso giorno";
                addV(violations, u.rows.get(0).excelRowNumber, "__assign__", reason);
                log.warn("[GREEDY] Day conflict for unit {}", u.id);
                // Popola errorMessage nelle righe corrispondenti
                for (FestivoInputRow row : u.rows) {
                    row.errorMessage = reason;
                }
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

    private double scoreAfterAssign(BuiltModel bm,
                                    Map<String, Integer> assignment,
                                    long[] pesi,
                                    Map<Integer, int[]> eventiPerMese,
                                    int teamCandidate,
                                    FestivoUnit u,
                                    double alpha) {
        // clone light
        long[] W = Arrays.copyOf(pesi, pesi.length);
        Map<Integer, int[]> E = new HashMap<>();
        for (int t = 1; t <= 10; t++) E.put(t, Arrays.copyOf(eventiPerMese.get(t), 12));

        // apply tentative assignment
        if ("MPB".equals(u.tipo)) {
            W[teamCandidate] += u.peso;
            E.get(teamCandidate)[u.month - 1] += 1;
        } else {
            W[teamCandidate] += u.peso;
            E.get(teamCandidate)[u.month - 1] += 1;
        }

        long maxW = Long.MIN_VALUE, minW = Long.MAX_VALUE, totW = 0;
        int maxE = Integer.MIN_VALUE, totE = 0;
        for (int t = 1; t <= 10; t++) {
            maxW = Math.max(maxW, W[t]);
            minW = Math.min(minW, W[t]);
            totW += W[t];
            int sumE = sum(E.get(t));
            maxE = Math.max(maxE, sumE);
            totE += sumE;
        }
        double Lprime = totW == 0 ? 0.0 : (double)(maxW - minW) / (double)totW;
        double EmaxPrime = totE == 0 ? 0.0 : (double)maxE / (double)totE;
        double score = alpha * Lprime + (1.0 - alpha) * EmaxPrime;
        return score;
    }

    private String buildNoCandidatesReason(FestivoUnit u, BuiltModel bm, int minProximityDays,
                                           Map<Integer, int[]> eventiPerMese, int[] eventiQuestoAnnoPesanti) {
        StringBuilder sb = new StringBuilder();
        sb.append("Nessuna squadra disponibile per data=").append(u.dates.get(0)).append(" turno=").append(u.tipo).append(". ");
        
        List<Integer> excluded = new ArrayList<>();
        List<Integer> proximityBlocked = new ArrayList<>();
        List<Integer> monthlyBlocked = new ArrayList<>();
        List<Integer> heavyBlocked = new ArrayList<>();
        
        // Analizza ogni squadra
        for (int team = 1; team <= 10; team++) {
            if (u.escluse.contains(team)) {
                excluded.add(team);
                continue;
            }
            if (u.forzata.isPresent() && u.forzata.get() != team) {
                continue; // forzatura esclude altre squadre implicitamente
            }
            
            // Proximity
            boolean proxOk = true;
            for (LocalDate d : u.dates) {
                if (!proximityOk(team, d, minProximityDays)) {
                    proxOk = false;
                    break;
                }
            }
            if (!proxOk) proximityBlocked.add(team);
            
            // Monthly
            if (eventiPerMese.get(team)[u.month - 1] >= 1) monthlyBlocked.add(team);
            
            // Heavy
            if (u.pesante && eventiQuestoAnnoPesanti[team] >= 1) heavyBlocked.add(team);
        }
        
        // Costruisci messaggio dettagliato
        sb.append("Vincoli violati: ");
        
        if (u.forzata.isPresent()) {
            sb.append("• Forzata a squadra ").append(u.forzata.get()).append(" che però viola altri vincoli. ");
        }
        
        if (!excluded.isEmpty()) {
            sb.append("• Escluse: ").append(excluded).append(". ");
        }
        
        if (!proximityBlocked.isEmpty()) {
            sb.append("• Bloccate per prossimità (minProximityDays=").append(minProximityDays).append("): ")
              .append(proximityBlocked).append(". ");
        }
        
        if (!monthlyBlocked.isEmpty()) {
            sb.append("• Già hanno un festivo nel mese ").append(u.month).append(": ")
              .append(monthlyBlocked).append(". ");
        }
        
        if (u.pesante && !heavyBlocked.isEmpty()) {
            sb.append("• Già hanno un festivo pesante nell'anno ").append(u.year).append(": ")
              .append(heavyBlocked).append(". ");
        }
        
        // Squadre libere (non bloccate da nessun vincolo)
        List<Integer> free = new ArrayList<>();
        for (int team = 1; team <= 10; team++) {
            if (!excluded.contains(team) && !proximityBlocked.contains(team) 
                && !monthlyBlocked.contains(team) && !heavyBlocked.contains(team)
                && (!u.forzata.isPresent() || u.forzata.get() == team)) {
                free.add(team);
            }
        }
        
        if (free.isEmpty()) {
            sb.append("Nessuna squadra rispetta tutti i vincoli. ");
        } else {
            sb.append("Squadre teoricamente libere: ").append(free).append(" (ma potrebbero violare vincoli MP/SN stesso giorno). ");
        }
        
        sb.append("Suggerimenti: riduci minProximityDays, rimuovi esclusioni/forzature, o verifica densità festivi.");
        return sb.toString();
    }
}


