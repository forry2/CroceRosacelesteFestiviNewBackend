package com.example.crocerosacelestefestivinewbackend.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/festivi/assegna")
public class FestiviController {

    @PostMapping(path = "/greedy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> assegnaGreedy(
            @RequestParam("file") MultipartFile file,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam("minProximityDays") Integer minProximityDays
    ) {
        // TODO: implementazione greedy
        byte[] excelBytes = new byte[0];
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assegnazioni_festivi.xlsx")
                .body(excelBytes);
    }

    @PostMapping(path = "/milp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> assegnaMilp(
            @RequestParam("file") MultipartFile file,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam("minProximityDays") Integer minProximityDays
    ) {
        // TODO: implementazione MILP (stub)
        byte[] excelBytes = new byte[0];
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assegnazioni_festivi.xlsx")
                .body(excelBytes);
    }
}


