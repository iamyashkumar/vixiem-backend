package com.velorix.backend.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;

@Configuration
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${spring.data.mongodb.uri:mongodb://localhost:27017/velorix}")
    private String rawMongoUri;

    @Value("${spring.data.mongodb.database:velorix}")
    private String dbName;

    @Override
    protected String getDatabaseName() {
        return dbName;
    }

    @Override
    @Bean
    public MongoClient mongoClient() {
        String cleanUri = rawMongoUri != null ? rawMongoUri.trim().replaceAll("[\\r\\n]", "") : "mongodb://localhost:27017/velorix";
        ConnectionString connectionString = new ConnectionString(cleanUri);
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .build();
        return MongoClients.create(mongoClientSettings);
    }
}
