package com.example.aichat.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 订单查询工具
 * 提供 Mock 的订单查询功能
 */
@Component
public class OrderTool {

    // Mock 订单数据
    private static final Map<String, OrderInfo> MOCK_ORDERS = new HashMap<>();

    static {
        // 初始化一些 Mock 订单数据
        MOCK_ORDERS.put("ORD001", new OrderInfo("ORD001", "张三", "已发货", 299.00, "2024-01-15"));
        MOCK_ORDERS.put("ORD002", new OrderInfo("ORD002", "李四", "配送中", 599.00, "2024-01-16"));
        MOCK_ORDERS.put("ORD003", new OrderInfo("ORD003", "王五", "已完成", 1299.00, "2024-01-10"));
        MOCK_ORDERS.put("ORD004", new OrderInfo("ORD004", "赵六", "待付款", 89.00, "2024-01-17"));
        MOCK_ORDERS.put("ORD005", new OrderInfo("ORD005", "钱七", "已取消", 459.00, "2024-01-12"));
    }

    /**
     * 根据订单号查询订单信息
     * @param orderId 订单号
     * @return 订单详细信息
     */
    @Tool("根据订单号查询订单状态、金额、收货人等信息")
    public String getOrderInfo(String orderId) {
        OrderInfo order = MOCK_ORDERS.get(orderId);
        
        if (order == null) {
            return "未找到订单号为 " + orderId + " 的订单信息。请检查订单号是否正确。";
        }
        
        return String.format(
            "【订单详情】\n" +
            "订单号：%s\n" +
            "收货人：%s\n" +
            "订单状态：%s\n" +
            "订单金额：¥%.2f\n" +
            "下单日期：%s",
            order.getOrderId(),
            order.getCustomerName(),
            order.getStatus(),
            order.getAmount(),
            order.getOrderDate()
        );
    }

    /**
     * 根据客户姓名查询订单列表
     * @param customerName 客户姓名
     * @return 订单列表信息
     */
    @Tool("根据客户姓名查询该客户的所有订单")
    public String getOrdersByCustomer(String customerName) {
        StringBuilder result = new StringBuilder();
        int count = 0;
        
        for (OrderInfo order : MOCK_ORDERS.values()) {
            if (order.getCustomerName().equals(customerName)) {
                if (count > 0) {
                    result.append("\n---\n");
                }
                result.append(String.format(
                    "订单号：%s | 状态：%s | 金额：¥%.2f | 日期：%s",
                    order.getOrderId(),
                    order.getStatus(),
                    order.getAmount(),
                    order.getOrderDate()
                ));
                count++;
            }
        }
        
        if (count == 0) {
            return "未找到客户 " + customerName + " 的订单信息。";
        }
        
        return String.format("【%s的订单列表】\n共找到 %d 个订单：\n%s", 
            customerName, count, result.toString());
    }

    /**
     * 内部类：订单信息
     */
    private static class OrderInfo {
        private final String orderId;
        private final String customerName;
        private final String status;
        private final double amount;
        private final String orderDate;

        public OrderInfo(String orderId, String customerName, String status, 
                        double amount, String orderDate) {
            this.orderId = orderId;
            this.customerName = customerName;
            this.status = status;
            this.amount = amount;
            this.orderDate = orderDate;
        }

        public String getOrderId() { return orderId; }
        public String getCustomerName() { return customerName; }
        public String getStatus() { return status; }
        public double getAmount() { return amount; }
        public String getOrderDate() { return orderDate; }
    }
}
