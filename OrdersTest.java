import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class OrdersTest {

    private static ReadContext ctx;

    @BeforeAll
    static void loadJson() throws Exception {
        File jsonFile = new File("orders.json");
        ctx = JsonPath.parse(jsonFile);
    }

    //  Presence & Format Validation 

    @Test
    void testOrderIdsPresent() {
        List<String> ids = ctx.read("$.orders[*].id");
        assertFalse(ids.isEmpty(), "No order IDs found");
        assertTrue(ids.stream().allMatch(id -> id != null && !id.trim().isEmpty()),
                "Some order IDs are missing or empty");
    }

    @Test
    void testOrderStatusesValid() {
        List<String> statuses = ctx.read("$.orders[*].status");
        Set<String> allowed = Set.of("PAID", "PENDING", "CANCELLED");
        assertTrue(statuses.stream().allMatch(allowed::contains),
                "Found invalid status: " + statuses);
    }

    @Test
    void testCstmrEmailFormat() {
        Pattern emailRegex = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
        List<Map<String, Object>> customers = ctx.read("$.orders[*].customer");

        List<String> badOrders = new ArrayList<>();
        for (Map<String, Object> customer : customers) {
            Object email = customer.get("email");
            if (email != null) {
                if (!emailRegex.matcher(email.toString()).matches()) {
                    badOrders.add(email.toString());
                }
            }
        }
        assertEquals(List.of("bob[at]example.com"), badOrders,
                "Invalid email addresses should be flagged");
    }

    @Test
    void testLineIntegrity() {
        List<Map<String, Object>> orders = ctx.read("$.orders[*]");
        for (Map<String, Object> order : orders) {
            String status = (String) order.get("status");
            List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");

            if (status.equals("PAID") || status.equals("PENDING")) {
                assertNotNull(lines, "Lines must not be null for " + order.get("id"));
                assertFalse(lines.isEmpty(), "Lines must not be empty for " + order.get("id"));
            }

            for (Map<String, Object> line : lines) {
                String sku = (String) line.get("sku");
                int qty = ((Number) line.get("qty")).intValue();
                double price = ((Number) line.get("price")).doubleValue();

                assertTrue(sku != null && !sku.isEmpty(), "SKU missing for " + order.get("id"));
                assertTrue(qty > 0, "Non-positive qty in " + order.get("id"));
                assertTrue(price >= 0, "Negative price in " + order.get("id"));
            }
        }
    }

    @Test
    void testPymtRefundConsistency() {
        List<Map<String, Object>> orders = ctx.read("$.orders[*]");
        for (Map<String, Object> order : orders) {
            String status = (String) order.get("status");
            String id = (String) order.get("id");

            Map<String, Object> payment = (Map<String, Object>) order.get("payment");
            if (status.equals("PAID")) {
                assertEquals(true, payment.get("captured"), "Paid order is not captured: " + id);
            }

            if (status.equals("CANCELLED")) {
                List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");
                if (lines != null && !lines.isEmpty()) {
                    double sum = lines.stream()
                            .mapToDouble(l -> ((Number) l.get("qty")).intValue() *
                                    ((Number) l.get("price")).doubleValue())
                            .sum();
                    Map<String, Object> refund = (Map<String, Object>) order.get("refund");
                    assertEquals(sum, ((Number) refund.get("amount")).doubleValue(),
                            0.001, "Refund mismatch in order " + id);
                }
            }
        }
    }

    @Test
    void testShippingFeeNonNegative() {
        List<Double> fees = ctx.read("$.orders[*].shipping.fee");
        assertTrue(fees.stream().allMatch(f -> f >= 0), "Found negative shipping fee");
    }

    //  Extraction & Aggregation 

    @Test
    void testOrderIdList() {
        List<String> ids = ctx.read("$.orders[*].id");
        assertEquals(List.of("A-1001","A-1002","A-1003","A-1004","A-1005"), ids);
    }

    @Test
    void testTtlLineItemsCount() {
        List<Map<String, Object>> lines = ctx.read("$.orders[*].lines[*]");
        assertEquals(8, lines.size());
    }

    @Test
    void testTop2SkusByQuantity() {
        List<Map<String, Object>> lines = ctx.read("$.orders[*].lines[*]");
        Map<String, Integer> totals = new HashMap<>();
        for (Map<String, Object> l : lines) {
            int qty = ((Number) l.get("qty")).intValue();
            if (qty > 0) {
                totals.merge((String) l.get("sku"), qty, Integer::sum);
            }
        }
        // Sorting by descending qty
        List<Map.Entry<String,Integer>> sorted = totals.entrySet().stream()
                .sorted((a,b) -> b.getValue() - a.getValue())
                .limit(2)
                .toList();

        assertEquals("PEN-RED", sorted.get(0).getKey());
        assertEquals(5, sorted.get(0).getValue());
        assertEquals("USB-32GB", sorted.get(1).getKey());
        assertEquals(2, sorted.get(1).getValue());
    }

    @Test
    void testGMVPerOrder() {
        Map<String, Double> expected = Map.of(
                "A-1001", 70.0,
                "A-1002", 0.0,
                "A-1003", -15.0,
                "A-1004", 16.0,
                "A-1005", 55.0
        );
        List<Map<String, Object>> orders = ctx.read("$.orders[*]");
        for (Map<String, Object> order : orders) {
            String id = (String) order.get("id");
            List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");
            double gmv = 0.0;
            if (lines != null) {
                for (Map<String, Object> l : lines) {
                    gmv += ((Number) l.get("qty")).intValue() *
                            ((Number) l.get("price")).doubleValue();
                }
            }
            assertEquals(expected.get(id), gmv, 0.001, "GMV mismatch for " + id);
        }
    }

    @Test
    void testOrdersWithMissingOrInvalidEmails() {
        List<String> orders = ctx.read("$.orders[*]");
        List<Map<String, Object>> all = ctx.read("$.orders[*]");
        List<String> badIds = new ArrayList<>();
        for (Map<String, Object> order : all) {
            Object email = ((Map<String,Object>) order.get("customer")).get("email");
            if (email == null || !email.toString().contains("@")) {
                badIds.add((String) order.get("id"));
            }
        }
        assertEquals(List.of("A-1002","A-1003"), badIds);
    }

    @Test
    void testPaidOrdersCaptured() {
        List<Map<String,Object>> paid = ctx.read("$.orders[?(@.status=='PAID')]");
        List<String> uncaptured = paid.stream()
                .filter(o -> !Boolean.TRUE.equals(((Map)o.get("payment")).get("captured")))
                .map(o -> (String)o.get("id"))
                .toList();
        assertEquals(List.of(), uncaptured);
    }

    @Test
    void testCancelledOrdersWithCorrectRefund() {
        List<Map<String,Object>> cancelled = ctx.read("$.orders[?(@.status=='CANCELLED')]");
        List<String> validIds = new ArrayList<>();
        for (Map<String,Object> o : cancelled) {
            List<Map<String,Object>> lines = (List<Map<String,Object>>) o.get("lines");
            double sum = lines.stream()
                    .mapToDouble(l -> ((Number) l.get("qty")).intValue() * ((Number) l.get("price")).doubleValue())
                    .sum();
            double refund = ((Number)((Map)o.get("refund")).get("amount")).doubleValue();
            if (Math.abs(refund - sum) < 0.001) {
                validIds.add((String)o.get("id"));
            }
        }
        assertEquals(List.of("A-1004"), validIds);
    }

    //  Reporting 

    @Test
    void testSummaryReport() {
        List<Map<String, Object>> orders = ctx.read("$.orders[*]");

        int totalOrders = orders.size();
        int totalLineItems = ctx.read("$.orders[*].lines[*]").size();

        List<String> problematic = new ArrayList<>();
        for (Map<String,Object> o : orders) {
            String id = (String) o.get("id");
            String reason = "";
            List<Map<String,Object>> lines = (List<Map<String,Object>>) o.get("lines");

            // bad email
            Object email = ((Map)o.get("customer")).get("email");
            if (email == null || !email.toString().contains("@")) {
                reason += " invalid email;";
            }
            // empty lines
            if ((lines == null || lines.isEmpty()) &&
                    !"CANCELLED".equals(o.get("status"))) {
                reason += " empty lines;";
            }
            // bad qty/price
            if (lines != null) {
                for (Map<String,Object> l : lines) {
                    int qty = ((Number) l.get("qty")).intValue();
                    double price = ((Number) l.get("price")).doubleValue();
                    if (qty <= 0 || price < 0) {
                        reason += " bad line data;";
                    }
                }
            }
            if (!reason.isEmpty()) {
                problematic.add(id + " =>" + reason);
            }
        }

        String summary = String.format(
                "Orders: %d, Lines: %d, Invalid: %d, Problems: %s",
                totalOrders, totalLineItems, problematic.size(), problematic
        );
        System.out.println(summary);

        assertTrue(summary.length() > 0);
    }
}
