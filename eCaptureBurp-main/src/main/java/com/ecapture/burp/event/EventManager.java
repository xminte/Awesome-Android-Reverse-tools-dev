package com.ecapture.burp.event;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages captured events, matches request-response pairs,
 * and sends matched pairs to Burp Proxy History.
 */
public class EventManager {
    
    private final MontoyaApi api;
    private final Logging logging;
    
    // Store all matched pairs for display
    private final List<MatchedHttpPair> matchedPairs;
    
    // Pending requests/responses waiting to be matched (by UUID)
    private final Map<String, MatchedHttpPair> pendingPairs;
    
    // Runtime logs from eCapture
    private final List<String> runtimeLogs;
    
    // Event listeners
    private final List<Consumer<MatchedHttpPair>> pairListeners;
    private final List<Consumer<String>> logListeners;
    
    // Stats
    private long totalEventsReceived;
    private long totalPairsMatched;
    private long lastHeartbeatTime;
    private long heartbeatCount;
    
    // Timeout for matching (5 minutes)
    private static final long MATCH_TIMEOUT_MS = 5 * 60 * 1000;
    
    public EventManager(MontoyaApi api) {
        this.api = api;
        this.logging = api.logging();
        this.matchedPairs = new CopyOnWriteArrayList<>();
        this.pendingPairs = new ConcurrentHashMap<>();
        this.runtimeLogs = new CopyOnWriteArrayList<>();
        this.pairListeners = new CopyOnWriteArrayList<>();
        this.logListeners = new CopyOnWriteArrayList<>();
        this.totalEventsReceived = 0;
        this.totalPairsMatched = 0;
        this.lastHeartbeatTime = 0;
        this.heartbeatCount = 0;
    }
    
    // Per-connection queues for request/response pairing
    // Key: connection UUID prefix (e.g., "sock:12345_67890_processname")
    // Value: list of pending requests waiting for responses
    private final Map<String, java.util.LinkedList<MatchedHttpPair>> pendingRequestsByConnection = new ConcurrentHashMap<>();
    
    /**
     * Extract connection ID from eCapture UUID.
     * UUID format: sock:pid_tid_processname_x_y_ip-ip_z
     * We extract: sock:pid_tid_processname (the first 4 parts joined by _)
     */
    private String extractConnectionId(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return "unknown";
        }
        
        // Format: sock:27570_27907_httpdns3_0_1_0.0.0.0:0-0.0.0.0:0_0
        // We want: sock:27570_27907_httpdns3 (ignore direction indicator _0_1_ or _0_0_)
        
        // Find the prefix before the direction indicators
        // Pattern: sock:PID_TID_NAME_X_Y_... where X_Y is direction (0_0 or 0_1 etc)
        String[] parts = uuid.split("_");
        if (parts.length >= 4) {
            // Take first 3 parts: sock:PID, TID, NAME
            StringBuilder connId = new StringBuilder();
            connId.append(parts[0]); // sock:PID
            connId.append("_").append(parts[1]); // TID
            connId.append("_").append(parts[2]); // NAME
            return connId.toString();
        }
        
        // Fallback: use the whole UUID
        return uuid;
    }
    
    /**
     * Process a captured event from eCapture.
     * Requests and responses are paired by connection (UUID prefix) in order.
     */
    public synchronized void processEvent(CapturedEvent event) {
        totalEventsReceived++;
        
        String uuid = event.getUuid();
        
        // Extract connection ID from UUID
        String connectionId = extractConnectionId(uuid);
        
        // Log event details for debugging
        if (event.isRequest()) {
            // Filter: only keep GET and POST requests with valid data
            String method = event.getHttpMethod();
            String url = event.getUrl();
            String host = event.getHost();
            
            // Accept GET and POST (case-insensitive)
            String upperMethod = method.toUpperCase();
            if (!upperMethod.equals("GET") && !upperMethod.equals("POST")) {
                return; // Skip non-GET/POST
            }
            
            // Skip requests with invalid/missing data
            if (url.equals("-") || url.isEmpty()) {
                return; // Skip requests without URL
            }
            if (host.equals("-") || host.isEmpty() || host.equals("0.0.0.0")) {
                return; // Skip requests without valid host
            }
            
            // Create a new pair for this request
            String pairId = connectionId + "_req_" + System.currentTimeMillis();
            MatchedHttpPair pair = new MatchedHttpPair(pairId);
            pair.setRequest(event);
            
            // Add to pending requests for this connection
            pendingRequestsByConnection
                    .computeIfAbsent(connectionId, k -> new java.util.LinkedList<>())
                    .add(pair);
            
            // Add to display list
            matchedPairs.add(pair);
            totalPairsMatched++;
            
            // Notify UI
            notifyPairListeners(pair);
            
        } else if (event.isResponse()) {
            // Filter: only keep valid HTTP responses (must have numeric status code)
            String statusCode = event.getStatusCode();
            
            // Skip responses without valid status codes
            if (statusCode.equals("-") || statusCode.isEmpty()) {
                return;
            }
            
            // Check if status code looks valid (should be numeric, like "200", "404")
            try {
                int code = Integer.parseInt(statusCode.trim());
                if (code < 100 || code > 599) {
                    return; // Invalid HTTP status code range
                }
            } catch (NumberFormatException e) {
                return; // Not a numeric status code
            }
            
            // Try to find a pending request to pair with
            java.util.LinkedList<MatchedHttpPair> pendingRequests = pendingRequestsByConnection.get(connectionId);
            
            if (pendingRequests != null && !pendingRequests.isEmpty()) {
                // Find first request without a response
                MatchedHttpPair pair = null;
                for (MatchedHttpPair p : pendingRequests) {
                    if (!p.hasResponse()) {
                        pair = p;
                        break;
                    }
                }
                
                if (pair != null) {
                    // Pair this response with the request
                    pair.setResponse(event);
                    
                    // Response paired successfully
                    
                    // Notify UI to update
                    notifyPairListeners(pair);
                    
                    // Try to send complete pair to Site Map
                    sendToSiteMapSafe(pair);
                } else {
                    // All requests already have responses, create standalone response
                    createStandaloneResponse(connectionId, event);
                }
            } else {
                // No pending requests for this connection, create standalone response
                createStandaloneResponse(connectionId, event);
            }
        }
        // Unknown types are silently ignored (binary/unparseable data)
        
        // Cleanup old connections periodically
        cleanupOldConnections();
    }
    
    /**
     * Create a standalone response entry (no matching request).
     * Note: We skip standalone responses since they're not useful without requests.
     */
    private void createStandaloneResponse(String connectionId, CapturedEvent event) {
        // Skip standalone responses - they're not useful without matching requests
    }
    
    /**
     * Cleanup old connection queues to prevent memory leaks.
     */
    private void cleanupOldConnections() {
        // Remove connections with all completed pairs (older than 5 minutes)
        long now = System.currentTimeMillis();
        pendingRequestsByConnection.entrySet().removeIf(entry -> {
            java.util.LinkedList<MatchedHttpPair> pairs = entry.getValue();
            if (pairs.isEmpty()) return true;
            // Remove if oldest pair is older than 5 minutes and all are complete
            MatchedHttpPair oldest = pairs.peekFirst();
            return oldest != null && 
                   (now - oldest.getCreatedAt() > 5 * 60 * 1000) && 
                   pairs.stream().allMatch(MatchedHttpPair::isComplete);
        });
    }
    
    /**
     * Handle a completed request-response pair.
     */
    private void completePair(MatchedHttpPair pair) {
        totalPairsMatched++;
        
        // Move from pending to completed
        pendingPairs.remove(pair.getUuid());
        matchedPairs.add(pair);
        
        // Send to Burp Site Map
        sendToSiteMapSafe(pair);
        
        pair.setSentToProxy(true);
        
        // Pair matched
    }
    
    /**
     * Safely send matched pair to Burp Site Map (Target tab).
     * Note: Montoya API doesn't support adding to Proxy History directly.
     */
    private void sendToSiteMapSafe(MatchedHttpPair pair) {
        try {
            CapturedEvent request = pair.getRequest();
            CapturedEvent response = pair.getResponse();
            
            if (request == null || request.getPayload() == null) {
                return;
            }
            
            // Get host from the pair (parsed from Host header)
            String host = pair.getHost();
            
            // Skip if host is invalid
            if (host == null || host.isEmpty() || host.equals("0.0.0.0") || host.equals("-")) {
                return;
            }
            
            // Get port - default to 443 for HTTPS hosts
            int port = pair.getPort();
            if (port <= 0) {
                port = 443;
            }
            boolean useHttps = (port == 443 || port == 8443);
            
            // Create HttpService
            HttpService httpService = HttpService.httpService(host, port, useHttps);
            
            // Parse and create HTTP request
            HttpRequest httpRequest = HttpRequest.httpRequest(httpService, new String(request.getPayload()));
            
            // Create response if available
            HttpResponse httpResponse = null;
            if (response != null && response.getPayload() != null) {
                httpResponse = HttpResponse.httpResponse(new String(response.getPayload()));
            }
            
            // Add to site map (only if we have both request and response)
            if (httpResponse != null) {
                HttpRequestResponse requestResponse = HttpRequestResponse.httpRequestResponse(
                        httpRequest, httpResponse);
                api.siteMap().add(requestResponse);
                // Added to Site Map
            }
            
        } catch (Exception e) {
            // Silently ignore errors - Site Map is optional
            logging.logToError("Site Map error (ignored): " + e.getMessage());
        }
    }
    
    /**
     * Process heartbeat from eCapture.
     */
    public void processHeartbeat(long timestamp, long count, String message) {
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.heartbeatCount = count;
    }
    
    /**
     * Process runtime log from eCapture.
     */
    public void processRuntimeLog(String logMessage) {
        runtimeLogs.add(logMessage);
        
        // Keep only last 1000 logs
        while (runtimeLogs.size() > 1000) {
            runtimeLogs.remove(0);
        }
        
        // Notify listeners
        notifyLogListeners(logMessage);
    }
    
    /**
     * Clean up old pending pairs that haven't been matched.
     */
    private void cleanupOldPairs() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, MatchedHttpPair> entry : pendingPairs.entrySet()) {
            MatchedHttpPair pair = entry.getValue();
            if (now - pair.getCreatedAt() > MATCH_TIMEOUT_MS) {
                toRemove.add(entry.getKey());
                
                // Add incomplete pair to display list anyway
                if (!matchedPairs.contains(pair)) {
                    matchedPairs.add(pair);
                }
            }
        }
        
        for (String uuid : toRemove) {
            pendingPairs.remove(uuid);
        }
    }
    
    /**
     * Add listener for new/updated pairs.
     */
    public void addPairListener(Consumer<MatchedHttpPair> listener) {
        pairListeners.add(listener);
    }
    
    /**
     * Add listener for runtime logs.
     */
    public void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
    }
    
    private void notifyPairListeners(MatchedHttpPair pair) {
        for (Consumer<MatchedHttpPair> listener : pairListeners) {
            try {
                listener.accept(pair);
            } catch (Exception e) {
                logging.logToError("Error in pair listener: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    private void notifyLogListeners(String log) {
        for (Consumer<String> listener : logListeners) {
            try {
                listener.accept(log);
            } catch (Exception e) {
                logging.logToError("Error in log listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Get all matched pairs (for display).
     */
    public List<MatchedHttpPair> getMatchedPairs() {
        return new ArrayList<>(matchedPairs);
    }
    
    /**
     * Get runtime logs.
     */
    public List<String> getRuntimeLogs() {
        return new ArrayList<>(runtimeLogs);
    }
    
    /**
     * Clear all data.
     */
    public void clear() {
        matchedPairs.clear();
        pendingPairs.clear();
        runtimeLogs.clear();
        totalEventsReceived = 0;
        totalPairsMatched = 0;
    }
    
    // Getters for stats
    public long getTotalEventsReceived() {
        return totalEventsReceived;
    }
    
    public long getTotalPairsMatched() {
        return totalPairsMatched;
    }
    
    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }
    
    public long getHeartbeatCount() {
        return heartbeatCount;
    }
    
    public int getPendingPairsCount() {
        return pendingPairs.size();
    }
}

