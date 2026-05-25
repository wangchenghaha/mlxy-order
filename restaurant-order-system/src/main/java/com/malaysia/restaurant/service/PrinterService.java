package com.malaysia.restaurant.service;

import com.malaysia.restaurant.common.enums.*;
import com.malaysia.restaurant.config.MqConfig;
import com.malaysia.restaurant.config.PrinterConfig;
import com.malaysia.restaurant.entity.Domain;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PrinterService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final byte[] TICKET_FEED = new byte[]{0x1B, 0x64, 0x08};
    private static final byte[] CUT = new byte[]{0x1D, 0x56, 0x01};
    private static final int STALE_SENDING_SECONDS = 30;
    private final InMemoryStore store;
    private final PrinterConfig config;
    private final MqConfig mqConfig;
    private final MqConfig.PrintTaskPublisher publisher;
    private final RealtimeEventService realtime;

    public PrinterService(InMemoryStore store, PrinterConfig config, MqConfig mqConfig,
                          MqConfig.PrintTaskPublisher publisher, RealtimeEventService realtime) {
        this.store = store;
        this.config = config;
        this.mqConfig = mqConfig;
        this.publisher = publisher;
        this.realtime = realtime;
    }

    @Async
    public void printKitchenOrder(Domain.Order order, PrintScene scene) {
        // Kitchen tickets are broadcast only to enabled kitchen printers under the same merchant.
        store.printers.values().stream()
                .filter(p -> p.merchantId() == order.merchantId() && p.storeId() == order.storeId()
                        && p.enabled() && p.type() == PrinterType.KITCHEN)
                .forEach(p -> submit(p, order.id(), scene, kitchenTicket(order, scene)));
    }

    @Async
    public void printKitchenAdditions(Domain.Order order, List<Domain.OrderItem> addedItems) {
        store.printers.values().stream()
                .filter(p -> p.merchantId() == order.merchantId() && p.storeId() == order.storeId()
                        && p.enabled() && p.type() == PrinterType.KITCHEN)
                .forEach(p -> submit(p, order.id(), PrintScene.KITCHEN_ADD_OR_RETURN,
                        kitchenTicket(order, PrintScene.KITCHEN_ADD_OR_RETURN, addedItems)));
    }

    @Async
    public void printReceipt(Domain.Order order, Domain.Payment payment) {
        // Cashier receipts go to front-desk printers; cashier PC only requests reprint/test actions.
        store.printers.values().stream()
                .filter(p -> p.merchantId() == order.merchantId() && p.storeId() == order.storeId()
                        && p.enabled() && p.type() == PrinterType.FRONT_DESK)
                .forEach(p -> submit(p, order.id(), PrintScene.FRONT_RECEIPT, cashierReceipt(order, payment)));
    }

    @Async
    public void printBill(Domain.Order order) {
        store.printers.values().stream()
                .filter(p -> p.merchantId() == order.merchantId() && p.storeId() == order.storeId()
                        && p.enabled() && p.type() == PrinterType.FRONT_DESK)
                .forEach(p -> submit(p, order.id(), PrintScene.FRONT_BILL, cashierBill(order)));
    }

    public Domain.PrintTask testPrint(long merchantId, long printerId) {
        Domain.Printer printer = store.printers.get(printerId);
        if (printer == null || printer.merchantId() != merchantId) {
            throw new IllegalArgumentException("打印机不存在");
        }
        return submit(printer, null, PrintScene.TEST, "*** TEST PRINT ***\n" + LocalDateTime.now().format(TIME) + "\n");
    }

    private Domain.PrintTask submit(Domain.Printer printer, Long orderId, PrintScene scene, String content) {
        Domain.PrintTask task = new Domain.PrintTask(store.nextId(), printer.merchantId(), printer.storeId(), orderId, printer.id(), scene,
                content, PrintStatus.WAITING, 0, null, null, LocalDateTime.now(), LocalDateTime.now());
        // Persist before sending so a crash does not silently lose the print job.
        store.savePrintTask(task);
        realtime.printTaskChanged(task.merchantId(), task.storeId(), task.id(), task.status().name());
        try {
            publisher.publish(task.id());
        } catch (Exception ignored) {
            // The database outbox poller below still dispatches WAITING tasks when MQ is unavailable.
        }
        return task;
    }

    public void executeTask(long taskId) {
        Domain.PrintTask task = store.queryPrintTaskById(taskId);
        if (task == null) {
            return;
        }
        Domain.Printer printer = store.queryPrinterById(task.printerId());
        if (printer != null && printer.enabled()) {
            execute(task, printer);
        }
    }

    void execute(Domain.PrintTask task, Domain.Printer printer) {
        try {
            if (!store.claimPrintTask(task.id(), task.status(), PrintStatus.SENDING)) {
                return;
            }
            realtime.printTaskChanged(task.merchantId(), task.storeId(), task.id(), PrintStatus.SENDING.name());
            // When dry-run is disabled, production sends ESC/POS bytes to WiFi printers.
            if (!config.isDryRun()) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(printer.ip(), printer.port()), config.getSocketTimeoutMs());
                    OutputStream out = socket.getOutputStream();
                    out.write(escPos(task.content()));
                    out.flush();
                }
            }
            Domain.PrintTask printed = task.printed();
            store.savePrintTask(printed);
            realtime.printTaskChanged(printed.merchantId(), printed.storeId(), printed.id(), printed.status().name());
        } catch (Exception e) {
            store.failClaimedPrintTask(task.id(), e.getMessage(), nextRetryAt(task.retryCount() + 1));
            Domain.PrintTask failed = store.queryPrintTaskById(task.id());
            if (failed == null) {
                return;
            }
            realtime.printTaskChanged(failed.merchantId(), failed.storeId(), failed.id(), failed.status().name());
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void dispatchWaitingTasks() {
        // A lightweight outbox poller keeps print submission async and survives printer/MQ downtime.
        List<Domain.PrintTask> waiting = store.queryPendingPrintTasks(50);
        for (Domain.PrintTask task : waiting) {
            Domain.Printer printer = store.queryPrinterById(task.printerId());
            if (printer != null && printer.enabled()) {
                execute(task, printer);
            }
        }
        List<Domain.PrintTask> staleSending = store.queryStaleSendingPrintTasks(STALE_SENDING_SECONDS, config.getMaxRetry());
        for (Domain.PrintTask task : staleSending) {
            Domain.Printer printer = store.queryPrinterById(task.printerId());
            if (printer != null && printer.enabled()) {
                execute(task, printer);
            }
        }
    }

    @RabbitListener(queues = "${restaurant.mq.print-queue}", autoStartup = "${restaurant.mq.enabled:false}")
    public void consumePrintTask(Long taskId) {
        if (taskId != null) {
            executeTask(taskId);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void retryFailedTasks() {
        // Failed jobs stay in MySQL and are retried until the configured max retry count.
        List<Domain.PrintTask> failed = store.queryFailedPrintTasks(config.getMaxRetry());
        for (Domain.PrintTask task : failed) {
            Domain.Printer printer = store.queryPrinterById(task.printerId());
            if (printer != null && printer.enabled()) {
                execute(task, printer);
            }
        }
    }

    private LocalDateTime nextRetryAt(int retryCount) {
        long delaySeconds = Math.min(300, (long) Math.pow(2, Math.max(1, retryCount)) * 5L);
        return LocalDateTime.now().plusSeconds(delaySeconds);
    }

    public String kitchenTicket(Domain.Order order, PrintScene scene) {
        return kitchenTicket(order, scene, order.items());
    }

    public String kitchenTicket(Domain.Order order, PrintScene scene, List<Domain.OrderItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append(scene == PrintScene.KITCHEN_ADD_OR_RETURN ? "*** ADD/RETURN ORDER ***\n" : "*** KITCHEN ORDER ***\n");
        sb.append("Table: ").append(order.tableNo()).append("   Order: ").append(order.id()).append('\n');
        sb.append("Time: ").append(order.createdAt().format(TIME)).append("   Waiter: ").append(order.waiterName()).append('\n');
        sb.append("--------------------------------\n");
        for (Domain.OrderItem item : items) {
            if (item.status() == OrderItemStatus.CANCELLED) {
                sb.append("RETURN ");
            }
            sb.append(item.quantity()).append(" x ").append(item.dishNameEn()).append(" / ").append(item.dishNameMs()).append('\n');
            if (item.remark() != null && !item.remark().isBlank()) {
                sb.append("  Note: ").append(item.remark()).append('\n');
            }
        }
        sb.append("--------------------------------\n");
        sb.append("Remark: ").append(order.remark() == null ? "-" : order.remark()).append("\n\n\n");
        return sb.toString();
    }

    public String cashierReceipt(Domain.Order order, Domain.Payment payment) {
        StringBuilder sb = new StringBuilder();
        Domain.Merchant merchant = store.merchants.get(order.merchantId());
        sb.append(merchant.nameEn()).append('\n').append(merchant.address()).append('\n').append(merchant.phone()).append('\n');
        sb.append("--------------------------------\n");
        sb.append("Receipt / Resit / 小票\n");
        sb.append("Order: ").append(order.id()).append(" Table: ").append(order.tableNo()).append('\n');
        sb.append("Paid at: ").append(payment.paidAt().format(TIME)).append('\n');
        sb.append("--------------------------------\n");
        for (Domain.OrderItem item : order.items()) {
            sb.append(item.dishNameEn()).append('\n');
            sb.append(item.quantity()).append(" x ").append(item.unitPrice()).append(" = ").append(item.subtotal()).append('\n');
        }
        sb.append("--------------------------------\n");
        sb.append("Total RM ").append(order.totalAmount()).append('\n');
        sb.append("Payment: ").append(payment.method()).append('\n');
        sb.append("Ref: ").append(payment.referenceNo() == null ? "-" : payment.referenceNo()).append('\n');
        sb.append("Thank you / Terima kasih / 谢谢\n\n\n");
        return sb.toString();
    }

    public String cashierBill(Domain.Order order) {
        StringBuilder sb = new StringBuilder();
        Domain.Merchant merchant = store.merchants.get(order.merchantId());
        sb.append(merchant.nameEn()).append('\n').append(merchant.address()).append('\n').append(merchant.phone()).append('\n');
        sb.append("--------------------------------\n");
        sb.append("Bill / Bil / 账单\n");
        sb.append("Order: ").append(order.id()).append(" Table: ").append(order.tableNo()).append('\n');
        sb.append("Time: ").append(LocalDateTime.now().format(TIME)).append('\n');
        sb.append("--------------------------------\n");
        for (Domain.OrderItem item : order.items()) {
            sb.append(item.dishNameEn()).append('\n');
            sb.append(item.quantity()).append(" x ").append(item.unitPrice()).append(" = ").append(item.subtotal()).append('\n');
        }
        sb.append("--------------------------------\n");
        sb.append("Total RM ").append(order.totalAmount()).append('\n');
        sb.append("Please pay at cashier / Sila bayar di kaunter / 请到收银台付款\n\n\n");
        return sb.toString();
    }

    private byte[] escPos(String text) {
        // GB18030 keeps Chinese receipt text printable on common ESC/POS thermal printers.
        // Feed a few extra lines before cutting so the tail of each ticket is fully ejected.
        byte[] body = text.getBytes(Charset.forName("GB18030"));
        byte[] bytes = new byte[body.length + TICKET_FEED.length + CUT.length];
        System.arraycopy(body, 0, bytes, 0, body.length);
        System.arraycopy(TICKET_FEED, 0, bytes, body.length, TICKET_FEED.length);
        System.arraycopy(CUT, 0, bytes, body.length + TICKET_FEED.length, CUT.length);
        return bytes;
    }
}
