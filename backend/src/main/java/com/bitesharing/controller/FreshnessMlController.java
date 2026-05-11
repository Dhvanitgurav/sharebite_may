package com.bitesharing.controller;

import com.bitesharing.dto.ErrorResponse;
import com.bitesharing.dto.FoodFreshnessResult;
import com.bitesharing.dto.FreshnessMlPredictResponse;
import com.bitesharing.service.FoodFreshnessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI freshness check — same JWT login as the rest of the app.
 * Engine (CNN vs Gemini vs hybrid) is chosen only via {@code freshness.mode} on the server.
 */
@Slf4j
@RestController
@RequestMapping("/api/freshness/ml")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class FreshnessMlController {

    private final FoodFreshnessService foodFreshnessService;

    /**
     * Hotel / donor: send the same image you will attach to the donation (upload or camera capture).
     */
    @PostMapping("/predict")
    public ResponseEntity<?> predict(@RequestParam("file") MultipartFile file) {
        try {
            String ct = file.getContentType();
            if (ct == null || !ct.startsWith("image/")) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Only image files are allowed", "INVALID_FILE_TYPE"));
            }

            FoodFreshnessResult result = foodFreshnessService.analyze(file.getBytes(), file.getOriginalFilename());
            FreshnessMlPredictResponse response = new FreshnessMlPredictResponse();
            response.setStatus(result.getStatus());
            response.setConfidence(result.getConfidence() != null ? result.getConfidence() : 0.0);
            Integer eh = result.getEstimatedExpiryHours();
            if (eh == null) {
                eh = "ROTTEN".equalsIgnoreCase(result.getStatus()) ? 0 : 4;
            }
            response.setEstimatedExpiryHours(eh);
            response.setMessage(result.getMessage() + " (" + result.getModelUsed() + ")");
            response.setClassProbabilities(result.getClassProbabilities());
            response.setPredictedClassIndex(result.getPredictedClassIndex());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage(), "INVALID_REQUEST"));
        } catch (IllegalStateException e) {
            log.error("Freshness ML error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse(e.getMessage(), "FRESHNESS_ML_UNAVAILABLE"));
        } catch (Exception e) {
            log.error("Freshness ML predict failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Freshness prediction failed: " + e.getMessage(), "FRESHNESS_ML_ERROR"));
        }
    }
}
