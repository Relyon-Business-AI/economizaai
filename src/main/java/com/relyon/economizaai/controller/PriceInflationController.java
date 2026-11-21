package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.PriceInflationResponse;
import com.relyon.economizaai.dto.response.PriceInflationStateResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PriceInflationScope;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.inflation.PriceInflationQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/insights/price-inflation")
@RequiredArgsConstructor
@Tag(name = "Price inflation", description = "Official food inflation (IBGE) next to the prices paid in the markets")
public class PriceInflationController {

    private final PriceInflationQueryService priceInflationQueryService;

    @GetMapping
    public ResponseEntity<PriceInflationResponse> overview(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "BR") PriceInflationScope scope,
            @RequestParam(required = false) UnidadeFederativa state,
            @RequestParam(required = false) Integer months) {
        return ResponseEntity.ok(priceInflationQueryService.overview(user, scope, state, months));
    }

    @GetMapping("/states")
    public ResponseEntity<List<PriceInflationStateResponse>> states(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Integer months) {
        return ResponseEntity.ok(priceInflationQueryService.states(user, months));
    }
}
