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
import java.util.concurrent.ConcurrentHashMap;
import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.LogEntry;
import org.springframework.data.mongodb.core.query.Query;

@Slf4j
@Service
public class AnalyticsService {

    @Autowired
    private MongoTemplate mongoTemplate;

    public List<Map> getDailyMetrics(List<String> userIds, String endpointId, int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        
        // 1. Match by any of user's IDs (mongoId or email)
        Criteria matchCriteria = Criteria.where("userId").in(userIds).and("timestamp").gte(startDate);
        
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

    private final Map<String, CachedSummary> summaryCache = new ConcurrentHashMap<>();

    private static class CachedSummary {
        final Map<String, Object> data;
        final long expiryTime;

        CachedSummary(Map<String, Object> data, long ttlMillis) {
            this.data = data;
            this.expiryTime = System.currentTimeMillis() + ttlMillis;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

    public Map<String, Object> getSummary(List<String> userIds) {
        return getSummary(userIds, null);
    }

    public Map<String, Object> getSummary(List<String> userIds, String workspaceId) {
        String cacheKey = (userIds != null && !userIds.isEmpty()) ? String.join(",", userIds) : "default";
        if (workspaceId != null && !workspaceId.trim().isEmpty()) {
            cacheKey += ":" + workspaceId;
        }
        CachedSummary cached = summaryCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }
        // Fetch endpoint stats
        Query query = new Query(Criteria.where("userId").in(userIds));
        if (workspaceId != null && !workspaceId.trim().isEmpty() && !"ALL".equalsIgnoreCase(workspaceId)) {
            query.addCriteria(Criteria.where("workspaceId").is(workspaceId));
        }
        List<ApiEndpoint> endpoints = mongoTemplate.find(query, ApiEndpoint.class);
        
        long totalEndpoints = endpoints.size();
        // Fix Boolean vs String type mismatch bug: lastStatus is Boolean (true = UP, false = DOWN, null = UNKNOWN/ACTIVE)
        long upEndpoints = endpoints.stream()
                .filter(e -> Boolean.TRUE.equals(e.getLastStatus()) || (e.getLastStatus() == null && e.isActive()))
                .count();
        long downEndpoints = endpoints.stream()
                .filter(e -> Boolean.FALSE.equals(e.getLastStatus()))
                .count();

        List<String> endpointIds = endpoints.stream().map(ApiEndpoint::getId).toList();
        
        // Fetch total requests (logs count)
        long totalRequests = 0;
        int averageResponseTime = 0;

        if (!endpointIds.isEmpty()) {
            Query logsQuery = new Query(Criteria.where("userId").in(userIds).and("endpointId").in(endpointIds));
            totalRequests = mongoTemplate.count(logsQuery, "logs");

            // Real-time live latency: average of the most recent 30 checks for instant responsiveness
            Query recentQuery = new Query(Criteria.where("userId").in(userIds)
                    .and("endpointId").in(endpointIds)
                    .and("responseTimeMs").ne(null))
                    .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "timestamp"))
                    .limit(30);
            List<LogEntry> recentLogs = mongoTemplate.find(recentQuery, LogEntry.class);
            if (!recentLogs.isEmpty()) {
                long sum = 0;
                for (LogEntry l : recentLogs) {
                    sum += l.getResponseTimeMs();
                }
                averageResponseTime = (int) Math.round((double) sum / recentLogs.size());
            }
        }
        
        long successfulRequests = totalRequests;
        double uptimePercentage = totalEndpoints > 0 ? 100.0 : 0.0;

        if (!endpointIds.isEmpty()) {
            LocalDateTime twentyFourHoursAgo = LocalDateTime.now().minusHours(24);
            Query query24h = new Query(Criteria.where("userId").in(userIds)
                    .and("endpointId").in(endpointIds)
                    .and("timestamp").gte(twentyFourHoursAgo));
            long requests24h = mongoTemplate.count(query24h, "logs");

            if (requests24h > 0) {
                Query errorQuery24h = new Query(Criteria.where("userId").in(userIds)
                        .and("endpointId").in(endpointIds)
                        .and("timestamp").gte(twentyFourHoursAgo)
                        .and("level").is("ERROR"));
                long error24h = mongoTemplate.count(errorQuery24h, "logs");
                long success24h = Math.max(0, requests24h - error24h);
                uptimePercentage = ((double) success24h / (double) requests24h) * 100.0;
                successfulRequests = success24h;
                totalRequests = requests24h;
            } else if (totalRequests > 0) {
                Query errorQuery = new Query(Criteria.where("userId").in(userIds)
                        .and("endpointId").in(endpointIds)
                        .and("level").is("ERROR"));
                long errorRequests = mongoTemplate.count(errorQuery, "logs");
                successfulRequests = Math.max(0, totalRequests - errorRequests);
                uptimePercentage = ((double) successfulRequests / (double) totalRequests) * 100.0;
            } else {
                uptimePercentage = totalEndpoints > 0 ? ((double) upEndpoints / (double) totalEndpoints) * 100.0 : 0.0;
            }
        }

        double fleetHealthPercentage = totalEndpoints > 0 ? ((double) upEndpoints / (double) totalEndpoints) * 100.0 : 0.0;
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalEndpoints", totalEndpoints);
        summary.put("upEndpoints", upEndpoints);
        summary.put("downEndpoints", downEndpoints);
        summary.put("averageResponseTime", averageResponseTime);
        summary.put("uptimePercentage", String.format("%.2f", uptimePercentage));
        summary.put("fleetHealthPercentage", String.format("%.2f", fleetHealthPercentage));
        summary.put("totalRequests", totalRequests);
        summary.put("successfulRequests", successfulRequests);
        summary.put("slaStatus", totalEndpoints == 0 ? "No Active Endpoints" : (uptimePercentage >= 99.0 ? "Healthy" : "At Risk"));
        
        summaryCache.put(cacheKey, new CachedSummary(summary, 1500));
        return summary;
    }
}
