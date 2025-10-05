package com.example.crocerosacelestefestivinewbackend.api;

import com.example.crocerosacelestefestivinewbackend.service.ExcelParsingService;
import com.example.crocerosacelestefestivinewbackend.service.ExcelParsingService.ParseResult;
import com.example.crocerosacelestefestivinewbackend.service.GreedySchedulerService;
import com.example.crocerosacelestefestivinewbackend.service.MilpSchedulerService;
import com.example.crocerosacelestefestivinewbackend.service.ExcelOutputService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
 

@RestController
@RequestMapping("/api/festivi/assegna")
public class FestiviController {

    private final ExcelParsingService excelParsingService;
    private final GreedySchedulerService greedySchedulerService;
    private final MilpSchedulerService milpSchedulerService;
    private final ExcelOutputService excelOutputService;
    private static final DateTimeFormatter STRICT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Logger log = LoggerFactory.getLogger(FestiviController.class);

    public FestiviController(ExcelParsingService excelParsingService,
                             GreedySchedulerService greedySchedulerService,
                             MilpSchedulerService milpSchedulerService,
                             ExcelOutputService excelOutputService) {
        this.excelParsingService = excelParsingService;
        this.greedySchedulerService = greedySchedulerService;
        this.milpSchedulerService = milpSchedulerService;
        this.excelOutputService = excelOutputService;
    }

    @GetMapping(path = "/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        try {
            byte[] bytes = null;
            // Prefer exact file name with '@'
            for (Resource res : new Resource[]{
                    new ClassPathResource("@festivi-template.xlsx"),
                    new ClassPathResource("festivi-template.xlsx"),
                    new FileSystemResource("src/main/resources/@festivi-template.xlsx"),
                    new FileSystemResource("src/main/resources/festivi-template.xlsx"),
                    new FileSystemResource("main/resources/@festivi-template.xlsx"),
                    new FileSystemResource("main/resources/festivi-template.xlsx")
            }) {
                if (res.exists()) { try (java.io.InputStream is = res.getInputStream()) { bytes = is.readAllBytes(); break; } }
            }
            if (bytes == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=@festivi-template.xlsx")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping(path = "/greedy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> assegnaGreedy(
            @RequestParam("file") MultipartFile file,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam("minProximityDays") Integer minProximityDays,
            @RequestParam(value = "alpha", required = false) Double alpha
    ) {
        long t0 = System.currentTimeMillis();
        LocalDate start = LocalDate.parse(startDate, STRICT_FMT);
        LocalDate end = LocalDate.parse(endDate, STRICT_FMT);
        double a = alpha == null ? 1.0 : alpha.doubleValue();
        if (a < 0.0 || a > 1.0) throw new ValidationException(java.util.List.of(java.util.Map.of(
                "row", 0,
                "field", "alpha",
                "message", "alpha deve essere tra 0 e 1"
        )));
        log.info("[GREEDY] Request received. file={}, startDate={}, endDate={}, minProximityDays={}, alpha={}", file.getOriginalFilename(), start, end, minProximityDays, a);
        ParseResult parsed = excelParsingService.parse(getStream(file), start, end);
        GreedySchedulerService.ScheduleResult res = greedySchedulerService.schedule(parsed.rows, parsed.pesanti, start, end, minProximityDays, a);
        byte[] xls = excelOutputService.buildOutput(res.rowsMutated, res.assignment, res.pesiPerMese, res.eventiPerMese);
        long dt = System.currentTimeMillis() - t0;
        log.info("[GREEDY] Completed. rows={}, durationMs={}", parsed.rows.size(), dt);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assegnazioni_festivi.xlsx")
                .body(xls);
    }

    @PostMapping(path = "/milp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> assegnaMilp(
            @RequestParam("file") MultipartFile file,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam("minProximityDays") Integer minProximityDays,
            @RequestParam(value = "alpha", required = false) Double alpha
    ) {
        long t0 = System.currentTimeMillis();
        LocalDate start = LocalDate.parse(startDate, STRICT_FMT);
        LocalDate end = LocalDate.parse(endDate, STRICT_FMT);
        double a = alpha == null ? 1.0 : alpha.doubleValue();
        if (a < 0.0 || a > 1.0) throw new ValidationException(java.util.List.of(java.util.Map.of(
                "row", 0,
                "field", "alpha",
                "message", "alpha deve essere tra 0 e 1"
        )));
        log.info("[MILP] Request received. file={}, startDate={}, endDate={}, minProximityDays={}, alpha={}", file.getOriginalFilename(), start, end, minProximityDays, a);
        ParseResult parsed = excelParsingService.parse(getStream(file), start, end);
        MilpSchedulerService.ScheduleResult res = milpSchedulerService.schedule(parsed.rows, parsed.pesanti, start, end, minProximityDays, a);
        byte[] xls = excelOutputService.buildOutput(res.rowsMutated, res.assignment, res.pesiPerMese, res.eventiPerMese);
        long dt = System.currentTimeMillis() - t0;
        log.info("[MILP] Completed. rows={}, durationMs={}", parsed.rows.size(), dt);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assegnazioni_festivi.xlsx")
                .body(xls);
    }

    private java.io.InputStream getStream(MultipartFile f) {
        try { return f.getInputStream(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}


