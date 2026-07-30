package com.velorix.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velorix.backend.model.AiSuggestion;
import com.velorix.backend.repository.AiSuggestionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class AiDebugService {

    @Autowired
    private AiSuggestionRepository aiSuggestionRepository;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${groq.api.key:}")
    private String groqApiKey;

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama-3.1-8b-instant";

    public Map<String, Object> analyzeErrors(String userId, String endpointId, List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            errors = List.of("Unknown endpoint failure or connection timeout");
        }

        String errorMessages = String.join("\n", errors);
        Map<String, Object> result;

        if (groqApiKey != null && !groqApiKey.trim().isEmpty() && !groqApiKey.equalsIgnoreCase("YOUR_GROQ_API_KEY")) {
            try {
                String prompt = "You are a senior DevOps and backend engineer. Analyze these API error logs and return ONLY a valid JSON object with exactly these keys: severity (\"HIGH\", \"MEDIUM\", or \"LOW\"), summary (string), rootCause (string), recommendations (array of string step-by-step solutions), confidence (number 0.0 to 1.0). Do NOT use markdown code blocks or additional text.\n\nError logs:\n" + errorMessages;
                String aiResponse = callGroqApi(prompt);
                result = parseAiResponse(aiResponse, errorMessages);
            } catch (Exception e) {
                log.warn("Groq API call failed ({}). Falling back to Rule-Based AI Engine.", e.getMessage());
                result = getSmartRuleBasedAnalysis(errors);
            }
        } else {
            log.info("Groq API Key not configured. Using Rule-Based Diagnostic Engine.");
            result = getSmartRuleBasedAnalysis(errors);
        }

        // Standardize solution field for frontend
        List<String> recs = (List<String>) result.getOrDefault("recommendations", Collections.emptyList());
        String solutionText = recs.isEmpty() ? "Check endpoint configuration and verify downstream service logs." : String.join("\n• ", recs);
        if (!solutionText.startsWith("• ")) {
            solutionText = "• " + solutionText;
        }
        result.put("solution", solutionText);

        // Save to MongoDB
        try {
            AiSuggestion suggestion = AiSuggestion.builder()
                    .userId(userId)
                    .endpointId(endpointId)
                    .possibleCause((String) result.getOrDefault("rootCause", "Service error"))
                    .recommendedFix(solutionText)
                    .severity((String) result.getOrDefault("severity", "MEDIUM"))
                    .originalErrors(errorMessages.length() > 500 ? errorMessages.substring(0, 500) : errorMessages)
                    .createdAt(LocalDateTime.now())
                    .build();
            aiSuggestionRepository.save(suggestion);
        } catch (Exception ex) {
            log.error("Failed to save AI suggestion to database: {}", ex.getMessage());
        }

        return result;
    }

    private String callGroqApi(String prompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("messages", List.of(message));
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 400);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(GROQ_URL, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = mapper.readTree(response.getBody());
            return root.path("choices").get(0).path("message").path("content").asText();
        } else {
            throw new RuntimeException("Groq API returned HTTP " + response.getStatusCode());
        }
    }

    private Map<String, Object> parseAiResponse(String raw, String rawErrors) {
        Map<String, Object> result = new HashMap<>();
        try {
            String jsonStr = raw.trim();
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.substring(7);
                int end = jsonStr.indexOf("```");
                if (end != -1) jsonStr = jsonStr.substring(0, end);
            } else if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.substring(3);
                int end = jsonStr.indexOf("```");
                if (end != -1) jsonStr = jsonStr.substring(0, end);
            }
            JsonNode node = mapper.readTree(jsonStr.trim());
            
            String rootCause = node.path("rootCause").asText("");
            if (rootCause.isEmpty() || rootCause.equalsIgnoreCase("Unknown")) {
                rootCause = node.path("summary").asText("API endpoint failed due to unhandled service exception.");
            }

            result.put("summary", node.path("summary").asText("API Error Analysis"));
            result.put("rootCause", rootCause);
            result.put("severity", node.path("severity").asText("HIGH"));
            result.put("confidence", node.path("confidence").asDouble(0.92));

            List<String> recs = new ArrayList<>();
            if (node.has("recommendations") && node.get("recommendations").isArray()) {
                for (JsonNode r : node.get("recommendations")) {
                    recs.add(r.asText());
                }
            }
            result.put("recommendations", recs);
        } catch (Exception e) {
            log.warn("Failed to parse Groq response JSON, fallback to smart rule engine");
            return getSmartRuleBasedAnalysis(List.of(rawErrors));
        }
        return result;
    }

    private Map<String, Object> getSmartRuleBasedAnalysis(List<String> errors) {
        Map<String, Object> mock = new HashMap<>();
        String errorText = String.join(" ", errors).toLowerCase();

        String rootCause;
        String severity = "HIGH";
        List<String> recs = new ArrayList<>();

        if (errorText.contains("401") || errorText.contains("unauthorized") || errorText.contains("token")) {
            rootCause = "HTTP 401 Unauthorized: Access token is missing, expired, or rejected by target authentication server.";
            severity = "HIGH";
            recs.add("Verify Authorization headers (Bearer token or JWT authentication cookie) sent in target requests.");
            recs.add("Check token expiration timestamp and ensure refresh token workflow is functioning.");
            recs.add("Ensure target endpoint route is included in Spring Security / API gateway permission whitelist.");
        } else if (errorText.contains("403") || errorText.contains("forbidden")) {
            rootCause = "HTTP 403 Forbidden: Authenticated user lacks required roles or permissions for this resource.";
            severity = "MEDIUM";
            recs.add("Check user role definitions (e.g. ROLE_USER vs ROLE_ADMIN) in backend security context.");
            recs.add("Verify CSRF protection rules or CORS origin headers for POST/PUT requests.");
        } else if (errorText.contains("404") || errorText.contains("not found")) {
            rootCause = "HTTP 404 Not Found: Requested URL path or resource identifier does not exist on target host.";
            severity = "MEDIUM";
            recs.add("Verify target URL path spelling and path parameters.");
            recs.add("Check router controller mappings (e.g. @RequestMapping) in target backend service.");
        } else if (errorText.contains("500") || errorText.contains("internal server error")) {
            rootCause = "HTTP 500 Internal Server Error: Unhandled exception or NullPointerException on target API server.";
            severity = "HIGH";
            recs.add("Inspect target backend application logs for unhandled stack trace.");
            recs.add("Verify database connection pools and query parameters.");
            recs.add("Wrap vulnerable code paths in try-catch blocks with descriptive error handling.");
        } else if (errorText.contains("timeout") || errorText.contains("connectexception") || errorText.contains("refused")) {
            rootCause = "Network Connection Failure: Target server is offline, unreachable, or port blocked by firewall.";
            severity = "HIGH";
            recs.add("Verify target host IP address, port binding, and active server status.");
            recs.add("Check firewall, CORS, and network routing policies between Vixiem and target server.");
        } else {
            String snippet = errors.get(0);
            if (snippet.length() > 140) snippet = snippet.substring(0, 140) + "...";
            rootCause = "Runtime Exception Detected: " + snippet;
            severity = "MEDIUM";
            recs.add("Inspect error stack trace and target service dependencies.");
            recs.add("Ensure required request body schema and headers are formatted properly.");
            recs.add("Configure automated retry rules in Vixiem endpoint settings.");
        }

        mock.put("summary", "Automated Error Log Diagnostics");
        mock.put("rootCause", rootCause);
        mock.put("severity", severity);
        mock.put("confidence", 0.95);
        mock.put("recommendations", recs);

        return mock;
    }
}