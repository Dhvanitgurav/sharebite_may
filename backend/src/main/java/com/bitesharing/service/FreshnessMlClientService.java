package com.bitesharing.service;

import com.bitesharing.dto.FreshnessMlPredictResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class FreshnessMlClientService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${freshness.api.base-url:http://localhost:8000}")
    private String freshnessApiBaseUrl;

    public FreshnessMlClientService(
            @Qualifier("freshnessRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper.copy();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public FreshnessMlPredictResponse predict(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file is required");
        }
        String original = file.getOriginalFilename();
        String filename = (original != null && !original.isBlank()) ? original : "food.jpg";
        return predictBytes(file.getBytes(), filename);
    }

    public FreshnessMlPredictResponse checkFoodFreshness(MultipartFile file) throws Exception {
        return predict(file);
    }

    /**
     * Same as {@link #predict(MultipartFile)} for bytes read from disk (server-side donation publish).
     */
    public FreshnessMlPredictResponse predictBytes(byte[] data, String filename) throws Exception {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Image bytes are required");
        }
        String base = freshnessApiBaseUrl.trim().replaceAll("/+$", "");
        String url = base + "/predict";
        String safeName = (filename != null && !filename.isBlank()) ? filename : "food.jpg";

        ByteArrayResource resource = new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Freshness service returned: " + response.getStatusCode());
            }
            return objectMapper.readValue(response.getBody(), FreshnessMlPredictResponse.class);
        } catch (RestClientException e) {
            log.warn("Freshness ML service unreachable at {}: {}", url, e.getMessage());
            throw new IllegalStateException(
                    "Food freshness AI is not available. Start the Python API (uvicorn) or check freshness.api.base-url.",
                    e);
        }
    }
}
