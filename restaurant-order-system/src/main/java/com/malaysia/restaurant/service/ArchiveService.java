package com.malaysia.restaurant.service;

import com.malaysia.restaurant.config.ArchiveConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ArchiveService {
    private final JdbcTemplate jdbc;
    private final ArchiveConfig config;
    private final InMemoryStore store;

    public ArchiveService(JdbcTemplate jdbc, ArchiveConfig config, InMemoryStore store) {
        this.jdbc = jdbc;
        this.config = config;
        this.store = store;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledArchive() {
        if (config.isEnabled()) {
            archiveOnce();
        }
    }

    @Transactional
    public ArchiveResult archiveOnce() {
        LocalDateTime now = LocalDateTime.now();
        int orders = archiveOrders(now.minusDays(config.getOrderRetentionDays()), config.getBatchSize());
        int logs = archiveSimple("operation_log", "operation_log_archive", "created_at",
                now.minusDays(config.getLogRetentionDays()), config.getBatchSize());
        int printTasks = archiveSimple("print_task", "print_task_archive", "created_at",
                now.minusDays(config.getPrintTaskRetentionDays()), config.getBatchSize());
        if (orders > 0 || logs > 0 || printTasks > 0) {
            store.reload();
        }
        return new ArchiveResult(orders, logs, printTasks);
    }

    private int archiveOrders(LocalDateTime before, int batchSize) {
        List<Long> orderIds = jdbc.queryForList("""
                        select id from order_main
                        where status in ('PAID', 'CANCELLED') and created_at < ?
                        order by created_at
                        limit ?
                        """,
                Long.class, Timestamp.valueOf(before), batchSize);
        if (orderIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        Object[] args = orderIds.toArray();
        jdbc.update("insert ignore into order_main_archive select * from order_main where id in (" + placeholders + ")", args);
        jdbc.update("insert ignore into order_item_archive select * from order_item where order_id in (" + placeholders + ")", args);
        jdbc.update("insert ignore into payment_archive select * from payment where order_id in (" + placeholders + ")", args);
        jdbc.update("delete from payment where order_id in (" + placeholders + ")", args);
        jdbc.update("delete from order_item where order_id in (" + placeholders + ")", args);
        jdbc.update("delete from order_main where id in (" + placeholders + ")", args);
        return orderIds.size();
    }

    private int archiveSimple(String source, String target, String timeColumn, LocalDateTime before, int batchSize) {
        int copied = jdbc.update("""
                        insert ignore into %s
                        select * from %s
                        where %s < ?
                        order by %s
                        limit ?
                        """.formatted(target, source, timeColumn, timeColumn),
                Timestamp.valueOf(before), batchSize);
        if (copied > 0) {
            jdbc.update("""
                            delete from %s
                            where %s < ?
                            order by %s
                            limit ?
                            """.formatted(source, timeColumn, timeColumn),
                    Timestamp.valueOf(before), copied);
        }
        return copied;
    }

    public record ArchiveResult(int orders, int logs, int printTasks) {
    }
}
