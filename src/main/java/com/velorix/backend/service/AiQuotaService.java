package com.velorix.backend.service;

import com.velorix.backend.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AiQuotaService {

    @Autowired
    private MongoTemplate mongoTemplate;

    // Minimum cooldown between consecutive AI calls to prevent DDoS burst attacks
    private static final int BURST_COOLDOWN_SECONDS = 10;

    public boolean reserveQuota(String userEmail) {
        User user = mongoTemplate.findOne(new Query(Criteria.where("email").is(userEmail)), User.class);
        if (user == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. BURST DDoS PROTECTION: Minimum 10-second cooldown between consecutive calls
        if (user.getLastApiCallTime() != null && user.getLastApiCallTime().plusSeconds(BURST_COOLDOWN_SECONDS).isAfter(now)) {
            System.out.println("⚠️ BURST ATTACK BLOCKED -> User " + userEmail + " attempted AI call within " + BURST_COOLDOWN_SECONDS + "s cooldown.");
            return false;
        }

        // 2. ROLLING 24-HOUR WINDOW (Fixes 11:59 PM vs 12:00 AM Midnight exploitation):
        // Credits reset exactly 24 HOURS after the first call in a cycle, NOT fixed calendar midnight!
        LocalDateTime resetThreshold = now.minusHours(24);
        boolean needsReset = user.getLastAiCallReset() == null || user.getLastAiCallReset().isBefore(resetThreshold);
        int maxCalls = user.getMaxDailyAiCalls() > 0 ? user.getMaxDailyAiCalls() : 50;

        if (needsReset) {
            user.setDailyAiCalls(1);
            user.setLastAiCallReset(now);
            user.setLastApiCallTime(now);
            user.setTotalAiCallsUsed(user.getTotalAiCallsUsed() + 1);
            mongoTemplate.save(user);
            return true;
        } else {
            if (user.getDailyAiCalls() < maxCalls) {
                user.setDailyAiCalls(user.getDailyAiCalls() + 1);
                user.setLastApiCallTime(now);
                user.setTotalAiCallsUsed(user.getTotalAiCallsUsed() + 1);
                mongoTemplate.save(user);
                return true;
            } else {
                return false;
            }
        }
    }
}
