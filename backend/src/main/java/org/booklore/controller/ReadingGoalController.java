package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.ReadingGoalDto;
import org.booklore.model.dto.request.ReadingGoalRequest;
import org.booklore.model.dto.response.YearlySummaryResponse;
import org.booklore.service.readthrough.ReadingGoalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reading-goal")
@AllArgsConstructor
@Tag(name = "Reading Goal", description = "Endpoints for managing yearly reading goals and retrieving yearly summaries")
public class ReadingGoalController {

    private final ReadingGoalService readingGoalService;

    @Operation(summary = "Get reading goal for a year", description = "Returns the authenticated user's reading goal for the specified year, if set.")
    @ApiResponse(responseCode = "200", description = "Goal returned")
    @ApiResponse(responseCode = "204", description = "No goal set for this year")
    @GetMapping("/{year}")
    @PreAuthorize("@securityUtil.canAccessUserStats() or @securityUtil.isAdmin()")
    public ResponseEntity<ReadingGoalDto> getGoal(
            @Parameter(description = "Year") @PathVariable int year) {
        return readingGoalService.getGoal(year)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @Operation(summary = "Set reading goal for a year", description = "Creates or updates the authenticated user's reading goal for the specified year.")
    @ApiResponse(responseCode = "200", description = "Goal saved")
    @PutMapping("/{year}")
    @PreAuthorize("@securityUtil.canAccessUserStats() or @securityUtil.isAdmin()")
    public ResponseEntity<ReadingGoalDto> setGoal(
            @Parameter(description = "Year") @PathVariable int year,
            @RequestBody ReadingGoalRequest request) {
        return ResponseEntity.ok(readingGoalService.setGoal(year, request));
    }

    @Operation(summary = "Delete reading goal for a year", description = "Removes the authenticated user's reading goal for the specified year.")
    @ApiResponse(responseCode = "204", description = "Goal deleted")
    @DeleteMapping("/{year}")
    @PreAuthorize("@securityUtil.canAccessUserStats() or @securityUtil.isAdmin()")
    public ResponseEntity<Void> deleteGoal(
            @Parameter(description = "Year") @PathVariable int year) {
        readingGoalService.deleteGoal(year);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get yearly reading summary", description = "Returns the authenticated user's yearly reading summary including goal progress, monthly breakdown, and completed books list.")
    @ApiResponse(responseCode = "200", description = "Summary returned")
    @GetMapping("/{year}/summary")
    @PreAuthorize("@securityUtil.canAccessUserStats() or @securityUtil.isAdmin()")
    public ResponseEntity<YearlySummaryResponse> getYearlySummary(
            @Parameter(description = "Year") @PathVariable int year) {
        return ResponseEntity.ok(readingGoalService.getYearlySummary(year));
    }
}
