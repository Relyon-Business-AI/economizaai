package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.PriceInflationRecomputeResponse;
import com.relyon.economizaai.service.inflation.PriceInflationRefreshJob;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/price-inflation")
@RequiredArgsConstructor
@Tag(name = "Admin · Price inflation", description = "On-demand load of IBGE months and recompute of the community index")
public class AdminPriceInflationController {

    private final PriceInflationRefreshJob refreshJob;

    @PostMapping("/recompute")
    public ResponseEntity<PriceInflationRecomputeResponse> recompute(@RequestParam(required = false) Integer months) {
        return ResponseEntity.ok(refreshJob.refreshOnDemand(months));
    }
}
