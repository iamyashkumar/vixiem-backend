package com.velorix.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.velorix.backend.model.ApiEndpoint;
import org.springframework.data.mongodb.core.query.Query;

@Slf4j
@Service
public class AnalyticsService {

    @Autowired
    private MongoTemplate mongoTemplate;

    public List<Map> getDailyMetrics(String userId, String endpointId, int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        
        // 1. MUST always match by userId first (IDOR prevention)
        Criteria matchCriteria = Criteria.where("userId").is(userId).and("timestamp").gte(startDate);
        
        // 2. Optionally filter by endpointId if provided
        if (endpointId != null && !endpointId.isEmpty()) {
            matchCriteria.and("endpointId").is(endpointId);
        }
        
        MatchOperation matchStage = Aggregation.match(matchCriteria);
        
        // 3. Project the day (date part only) from the timestamp for grouping
        ProjectionOperation projectStage = Aggregation.project()
                .andExpression("{$dateToString: {format: '%Y-%m-%d', date: '$timestamp'}}").as("date")
                .and("responseTimeMs").as("responseTimeMs")
                .and("level").as("level");
                
        // 4. Group by date
        GroupOperation groupStage = Aggregation.group("date")
                .avg("responseTimeMs").as("avgResponseTime")
                .sum(ConditionalOperators.when(Criteria.where("level").is("ERROR")).then(1).otherwise(0)).as("errorCount")
                .count().as("totalChecks");
                
        // 5. Sort by date ascending
        SortOperation sortStage = Aggregation.sort(Sort.Direction.ASC, "_id");
        
        // 6. Final projection to rename _id to date
        ProjectionOperation finalProject = Aggregation.project()
                .and("_id").as("date")
                .and("avgResponseTime").as("avgResponseTime")
                .and("errorCount").as("errorCount")
                .and("totalChecks").as("totalChecks")
                .andExclude("_id");

        Aggregation aggregation = Aggregation.newAggregation(
                matchStage,
                projectStage,
                groupStage,
                sortStage,
                finalProject
        );
        
        AggregationResults<Map> results = mongoTemplate.aggregate(aggregation, "logs", Map.class);
        return results.getMappedResults();
    }

    public Map<String, Object> getSummary(String userId) {
        // Fetch endpoint stats
        Query query = new Query(Criteria.where("userId").is(userId));
        List<ApiEndpoint> endpoints = mongoTemplate.find(query, ApiEndpoint.class);
        
        long totalEndpoints = endpoints.size();
        // Fix Boolean vs String type mismatch bug: lastStatus is Boolean (true = UP, false = DOWN, null = UNKNOWN/ACTIVE)
        long upEndpoints = endpoints.stream()
                .filter(e -> Boolean.TRUE.equals(e.getLastStatus()) || (e.getLastStatus() == null && e.isActive()))
                .count();
        long downEndpoints = endpoints.stream()
                .filter(e -> Boolean.FALSE.equals(e.getLastStatus()))
                .count();
        
        // Fetch total requests (logs count)
        long totalRequests = mongoTemplate.count(new Query(Criteria.where("userId").is(userId)), "logs");
        
        // Average response time from recent metrics (approximate by calling getDailyMetrics for 7 days)
        List<Map> metrics = getDailyMetrics(userId, null, 7);
        double totalAvg = 0;
        int validDays = 0;
        for (Map m : metrics) {
            if (m.get("avgResponseTime") != null) {
                totalAvg += ((Number) m.get("avgResponseTime")).doubleValue();
                validDays++;
            }
        }
        int averageResponseTime = validDays > 0 ? (int) Math.round(totalAvg / validDays) : 0;
        
        double uptimePercentage = totalEndpoints > 0 ? ((double) upEndpoints / (double) totalEndpoints) * 100.0 : 100.0;
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalEndpoints", totalEndpoints);
        summary.put("upEndpoints", upEndpoints);
        summary.put("downEndpoints", downEndpoints);
        summary.put("averageResponseTime", averageResponseTime);
        summary.put("uptimePercentage", String.format("%.2f", uptimePercentage));
        summary.put("totalRequests", totalRequests);
        summary.put("slaStatus", uptimePercentage >= 99.0 ? "Healthy" : "At Risk");
        
        return summary;
    }
}
