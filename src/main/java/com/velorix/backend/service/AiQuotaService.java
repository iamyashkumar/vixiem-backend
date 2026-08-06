package com.velorix.backend.service;

import com.velorix.backend.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AiQuotaService {

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final int BURST_COOLDOWN_SECONDS = 10;

    public synchronized boolean reserveQuota(String userEmail) {
        User user = mongoTemplate.findOne(new Query(Criteria.where("email").is(userEmail)), User.class);
        if (user == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. BURST DDoS PROTECTION: Minimum 10-second cooldown between consecutive calls
        if (user.getLastApiCallTime() != null && user.getLastApiCallTime().plusSeconds(BURST_COOLDOWN_SECONDS).isAfter(now)) {
            log.warn("BURST ATTACK BLOCKED -> User {} attempted AI call within {}s cooldown.", userEmail, BURST_COOLDOWN_SECONDS);
            return false;
        }

        LocalDateTime resetThreshold = now.minusHours(24);
        boolean needsReset = user.getLastAiCallReset() == null || user.getLastAiCallReset().isBefore(resetThreshold);
        int maxCalls = user.getMaxDailyAiCalls() > 0 ? user.getMaxDailyAiCalls() : 50;

        if (needsReset) {
            Query query = new Query(Criteria.where("email").is(userEmail));
            Update update = new Update()
                    .set("dailyAiCalls", 1)
                    .set("lastAiCallReset", now)
                    .set("lastApiCallTime", now)
                    .inc("totalAiCallsUsed", 1);

            mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), User.class);
            return true;
        } else {
            // Atomic conditional increment: dailyAiCalls < maxCalls
            Query query = new Query(Criteria.where("email").is(userEmail).and("dailyAiCalls").lt(maxCalls));
            Update update = new Update()
                    .inc("dailyAiCalls", 1)
                    .set("lastApiCallTime", now)
                    .inc("totalAiCallsUsed", 1);

            User updatedUser = mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), User.class);
            return updatedUser != null;
        }
    }
}
