package com.malaysia.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "restaurant.archive")
public class ArchiveConfig {
    private boolean enabled = true;
    private int orderRetentionDays = 365;
    private int logRetentionDays = 180;
    private int printTaskRetentionDays = 90;
    private int batchSize = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOrderRetentionDays() {
        return orderRetentionDays;
    }

    public void setOrderRetentionDays(int orderRetentionDays) {
        this.orderRetentionDays = orderRetentionDays;
    }

    public int getLogRetentionDays() {
        return logRetentionDays;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = logRetentionDays;
    }

    public int getPrintTaskRetentionDays() {
        return printTaskRetentionDays;
    }

    public void setPrintTaskRetentionDays(int printTaskRetentionDays) {
        this.printTaskRetentionDays = printTaskRetentionDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
