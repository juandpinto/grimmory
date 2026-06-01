package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.BookReadthroughDto;
import org.booklore.model.dto.request.BookReadthroughRequest;
import org.booklore.service.readthrough.BookReadthroughService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/books/{bookId}/readthroughs")
@AllArgsConstructor
@Tag(name = "Book Readthroughs", description = "Endpoints for managing per-book read-through history")
public class BookReadthroughController {

    private final BookReadthroughService bookReadthroughService;

    @Operation(summary = "List readthroughs for a book", description = "Returns all recorded readthroughs for the authenticated user for a specific book.")
    @ApiResponse(responseCode = "200", description = "Readthroughs returned successfully")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<BookReadthroughDto>> list(
            @Parameter(description = "ID of the book") @PathVariable @Positive Long bookId) {
        return ResponseEntity.ok(bookReadthroughService.listForBook(bookId));
    }

    @Operation(summary = "Create a readthrough", description = "Manually record a readthrough for the authenticated user for a specific book.")
    @ApiResponse(responseCode = "200", description = "Readthrough created successfully")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BookReadthroughDto> create(
            @Parameter(description = "ID of the book") @PathVariable @Positive Long bookId,
            @RequestBody BookReadthroughRequest request) {
        return ResponseEntity.ok(bookReadthroughService.create(bookId, request));
    }

    @Operation(summary = "Update a readthrough", description = "Update an existing readthrough. The readthrough must belong to the authenticated user.")
    @ApiResponse(responseCode = "200", description = "Readthrough updated successfully")
    @PutMapping("/{readthroughId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BookReadthroughDto> update(
            @Parameter(description = "ID of the book") @PathVariable @Positive Long bookId,
            @Parameter(description = "ID of the readthrough") @PathVariable @Positive Long readthroughId,
            @RequestBody BookReadthroughRequest request) {
        return ResponseEntity.ok(bookReadthroughService.update(readthroughId, request));
    }

    @Operation(summary = "Delete a readthrough", description = "Delete a readthrough. The readthrough must belong to the authenticated user.")
    @ApiResponse(responseCode = "204", description = "Readthrough deleted successfully")
    @DeleteMapping("/{readthroughId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(
            @Parameter(description = "ID of the book") @PathVariable @Positive Long bookId,
            @Parameter(description = "ID of the readthrough") @PathVariable @Positive Long readthroughId) {
        bookReadthroughService.delete(readthroughId);
        return ResponseEntity.noContent().build();
    }
}
