import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OrdersTest1 {

    private static ReadContext json;  // Used to read JSON using JsonPath

    @BeforeAll
    static void loadJson() throws Exception {
        // Loading the JSON file once before running tests
        // JsonPath.parse() returns a context object (json) which we can query
        json = JsonPath.parse(new File("orders.json"));
    }

    
    @Test
    void testOrderIdsAndStatus() {
        // Read all IDs as a List of Strings
        List<String> ids = json.read("$.orders[*].id");

        // Read all statuses as a List of Strings
        List<String> statuses = json.read("$.orders[*].status");

        // Allowed values for status
        Set<String> allowed = Set.of("PAID", "PENDING", "CANCELLED");

        // Check each order ID is present and not blank
        for (String id : ids) {
            assertNotNull(id, "Order ID missing");
            assertFalse(id.isBlank(), "Order ID is blank");
        }

        // Check each status is one of allowed
        for (String st : statuses) {
            assertTrue(allowed.contains(st), "Invalid status: " + st);
        }
    }

    @Test
    void testCustomerEmails() {
        // Here we read each "customer" object into a Map (key = field name, value = field value)
        // Example: {"id":201, "email":"abc@example.com"}
        List<Map<String, Object>> customers = json.read("$.orders[*].customer");

        for (Map<String, Object> cust : customers) {
            Object email = cust.get("email");  // May be null
            if (email != null) {
                // Email should contain "@"
                assertTrue(email.toString().contains("@"),
                        "Invalid email: " + email);
            }
        }
    }

    @Test
    void testLinesAndPayments() {
        // Each order itself is a Map
        // Example: {"id":"A-1001","status":"PAID","lines":[...],"payment":{...}}
        List<Map<String, Object>> orders = json.read("$.orders[*]");

        for (Map<String, Object> order : orders) {
            String id = order.get("id").toString();
            String status = order.get("status").toString();

            // "lines" is an array, so JsonPath gives us a List of Maps
            List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");

            // Check: PAID or PENDING orders must have at least 1 line
            if (status.equals("PAID") || status.equals("PENDING")) {
                assertNotNull(lines, id + " has no lines");
                assertFalse(lines.isEmpty(), id + " has empty lines");
            }

            // Each line should have valid quantity and price
            if (lines != null) {
                for (Map<String, Object> line : lines) {
                    // We use Integer.parseInt and Double.parseDouble
                    // because JsonPath stores numbers as generic Object (Number)
                    int qty = Integer.parseInt(line.get("qty").toString());
                    double price = Double.parseDouble(line.get("price").toString());

                    assertTrue(qty > 0, id + " has invalid qty: " + qty);
                    assertTrue(price >= 0, id + " has invalid price: " + price);
                }
            }

            // For PAID orders → payment.captured must be true
            Map<String, Object> payment = (Map<String, Object>) order.get("payment");
            if (status.equals("PAID")) {
                assertEquals(true, payment.get("captured"), id + " not captured");
            }

            // For CANCELLED orders → refund amount should equal sum of line totals
            if (status.equals("CANCELLED") && lines != null) {
                double total = 0;
                for (Map<String, Object> line : lines) {
                    int qty = Integer.parseInt(line.get("qty").toString());
                    double price = Double.parseDouble(line.get("price").toString());
                    total += qty * price;
                }
                Map<String, Object> refund = (Map<String, Object>) order.get("refund");
                double refundAmount = Double.parseDouble(refund.get("amount").toString());
                assertEquals(total, refundAmount, 0.01, id + " refund mismatch");
            }
        }
    }

    @Test
    void testShippingFees() {
        // Read all shipping fees into a List<Double>
        List<Double> fees = json.read("$.orders[*].shipping.fee");

        for (double fee : fees) {
            assertTrue(fee >= 0, "Negative fee: " + fee);
        }
    }

    

    @Test
    void testOrderIds() {
        List<String> ids = json.read("$.orders[*].id");
        // Check exact expected IDs
        assertEquals(List.of("A-1001","A-1002","A-1003","A-1004","A-1005"), ids);
    }

    @Test
    void testTotalLineItems() {
        // Read all lines from all orders
        List<Map<String, Object>> allLines = json.read("$.orders[*].lines[*]");
        assertEquals(8, allLines.size()); // Expecting 8 items
    }

    @Test
    void testTopSkus() {
        List<Map<String, Object>> allLines = json.read("$.orders[*].lines[*]");
        Map<String, Integer> totals = new HashMap<>();

        for (Map<String, Object> line : allLines) {
            int qty = Integer.parseInt(line.get("qty").toString());
            if (qty > 0) {
                String sku = line.get("sku").toString();
                // Count total quantity for each SKU
                totals.put(sku, totals.getOrDefault(sku, 0) + qty);
            }
        }

      
        // Top 2 SKUs
        assertEquals("PEN-RED", sorted.get(0).getKey());
        assertEquals(5, sorted.get(0).getValue());
        assertEquals("USB-32GB", sorted.get(1).getKey());
        assertEquals(2, sorted.get(1).getValue());
    }

    @Test
    void testGMVPerOrder() {
        // Expected Gross Merchandise Value (qty * price)
        Map<String, Double> expected = Map.of(
                "A-1001", 70.0,
                "A-1002", 0.0,
                "A-1003", -15.0,
                "A-1004", 16.0,
                "A-1005", 55.0
        );

        List<Map<String, Object>> orders = json.read("$.orders[*]");
        for (Map<String, Object> order : orders) {
            String id = order.get("id").toString();
            List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");

            double gmv = 0;
            if (lines != null) {
                for (Map<String, Object> line : lines) {
                    int qty = Integer.parseInt(line.get("qty").toString());
                    double price = Double.parseDouble(line.get("price").toString());
                    gmv += qty * price;
                }
            }
            assertEquals(expected.get(id), gmv, 0.01, "Wrong GMV for " + id);
        }
    }


    @Test
    void testSummaryReport() {
        List<Map<String, Object>> orders = json.read("$.orders[*]");
        int totalOrders = orders.size();

        int totalItems = json.read("$.orders[*].lines[*]").size();
        List<String> problems = new ArrayList<>();

        for (Map<String, Object> order : orders) {
            String id = order.get("id").toString();
            String status = order.get("status").toString();
            Map<String, Object> cust = (Map<String, Object>) order.get("customer");
            List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");

            List<String> issues = new ArrayList<>();

            // Email check
            Object email = cust.get("email");
            if (email == null || !email.toString().contains("@")) {
                issues.add("bad email");
            }

            // Empty lines check
            if ((lines == null || lines.isEmpty()) && !"CANCELLED".equals(status)) {
                issues.add("empty lines");
            }

            // Bad qty/price check
            if (lines != null) {
                for (Map<String, Object> line : lines) {
                    int qty = Integer.parseInt(line.get("qty").toString());
                    double price = Double.parseDouble(line.get("price").toString());
                    if (qty <= 0) issues.add("bad qty");
                    if (price < 0) issues.add("bad price");
                }
            }

            if (!issues.isEmpty()) {
                problems.add(id + " -> " + issues);
            }
        }

        String summary = "Orders=" + totalOrders +
                         ", Items=" + totalItems +
                         ", Problems=" + problems;
        System.out.println(summary);

        // Just check summary is generated
        assertTrue(summary.length() > 0);
    }
}
