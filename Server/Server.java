package Server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {
    private static final int PORT = 1234;
    private static final int DASHBOARD_PORT = 8080;
    private static final int STATS_PERIOD_SECONDS = 10;
    private static final int MAX_RECENT_LOGS = 200;
    private static final AtomicInteger CLIENT_ID_COUNTER = new AtomicInteger(1);
    private static final AtomicBoolean SHUTDOWN_DONE = new AtomicBoolean(false);
    private static final Map<String, AgentInfo> ACTIVE_AGENTS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService STATS_EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static final List<SseClient> SSE_CLIENTS = new CopyOnWriteArrayList<>();
    private static final Deque<String> RECENT_LOGS = new ConcurrentLinkedDeque<>();
    private static final Path DASHBOARD_DIR = findDashboardDir();
    private static HttpServer dashboardWebServer;

    public static void main(String[] args) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> shutdownResources("JVM shutdown hook"),
            "clusterstat-shutdown-hook"
        ));

        startDashboardWebServer();
        startPeriodicStatsTask();

        try (ServerSocket ss = new ServerSocket(PORT)) {
            logInfo("Server is running on port " + PORT + "...");
            logInfo("Dashboard is available at http://localhost:" + DASHBOARD_PORT);

            while (!ss.isClosed()) {
                try {
                    logInfo("Waiting for a connection...");
                    Socket s = ss.accept();
                    int tempId = CLIENT_ID_COUNTER.getAndIncrement();
                    Thread clientThread = new Thread(new ClientHandler(s, tempId), "client-thread-" + tempId);
                    clientThread.start();
                } catch (IOException e) {
                    logError("Accept error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logError("Server error: " + e.getMessage());
        } finally {
            shutdownResources("Main finally block");
        }
    }

    private static void shutdownResources(String origin) {
        if (!SHUTDOWN_DONE.compareAndSet(false, true)) {
            return;
        }
        try { STATS_EXECUTOR.shutdownNow(); } catch (Exception ignored) {}
        try { if (dashboardWebServer != null) dashboardWebServer.stop(0); } catch (Exception ignored) {}
        for (SseClient client : SSE_CLIENTS) client.closeSilently();
        SSE_CLIENTS.clear();
        ACTIVE_AGENTS.clear();
        RECENT_LOGS.clear();
        System.out.println("Server shutdown completed (" + origin + ").");
    }

    private static void startDashboardWebServer() {
        try {
            dashboardWebServer = HttpServer.create(new InetSocketAddress(DASHBOARD_PORT), 0);
            dashboardWebServer.createContext("/", exchange -> serveStaticFile(exchange, "index.html", "text/html; charset=utf-8"));
            dashboardWebServer.createContext("/styles.css", exchange -> serveStaticFile(exchange, "styles.css", "text/css; charset=utf-8"));
            dashboardWebServer.createContext("/script.js", exchange -> serveStaticFile(exchange, "script.js", "application/javascript; charset=utf-8"));
            dashboardWebServer.createContext("/api/state", Server::serveStateJson);
            dashboardWebServer.createContext("/api/events", Server::handleSse);
            dashboardWebServer.createContext("/api/export", Server::handleExport);
            dashboardWebServer.createContext("/api/spawn-agent", Server::handleSpawnAgent);
            dashboardWebServer.setExecutor(Executors.newCachedThreadPool());
            dashboardWebServer.start();
        } catch (IOException e) {
            logError("Unable to start dashboard web server: " + e.getMessage());
        }
    }

    private static void serveStaticFile(HttpExchange exchange, String fileName, String contentType) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        Path filePath = DASHBOARD_DIR.resolve(fileName).normalize();
        if (!Files.exists(filePath)) {
            sendResponse(exchange, 404, "File not found", "text/plain");
            return;
        }
        byte[] bytes = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private static Path findDashboardDir() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path cursor = cwd;
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path candidate = cursor.resolve("dashboard");
            if (Files.isDirectory(candidate) && Files.exists(candidate.resolve("index.html"))) return candidate;
            cursor = cursor.getParent();
        }
        return cwd.resolve("dashboard").normalize();
    }

    private static void serveStateJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        sendResponse(exchange, 200, buildStateJson(), "application/json; charset=utf-8");
    }

    private static void handleSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(exchange.getResponseBody());
        SSE_CLIENTS.add(client);
        client.sendEvent("state", buildStateJson());
    }

    private static void handleExport(HttpExchange exchange) throws IOException {
        StringBuilder csv = new StringBuilder("agent_id,timestamp,cpu_pct,ram_mb\n");
        for (AgentInfo info : ACTIVE_AGENTS.values()) {
            synchronized (info) {
                for (MetricSample s : info.samples) {
                    csv.append(info.agentId).append(",")
                       .append(s.timestamp).append(",")
                       .append(String.format(Locale.US, "%.2f", s.cpu)).append(",")
                       .append(String.format(Locale.US, "%.2f", s.memory)).append("\n");
                }
            }
        }
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=metrics.csv");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
    }

    private static void handleSpawnAgent(HttpExchange exchange) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", ".", "Client.Client");
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            pb.start();
            logInfo("Spawned a new agent process via API.");
            sendResponse(exchange, 200, "{\"status\":\"ok\"}", "application/json");
        } catch (Exception e) {
            logError("Failed to spawn agent: " + e.getMessage());
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}", "application/json");
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private static void broadcastState() { broadcastEvent("state", buildStateJson()); }

    private static void broadcastEvent(String eventName, String payloadJson) {
        for (SseClient client : SSE_CLIENTS) {
            try { client.sendEvent(eventName, payloadJson); }
            catch (IOException e) { client.closeSilently(); SSE_CLIENTS.remove(client); }
        }
    }

    private static String buildStateJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"activeClients\":[");
        List<AgentInfo> agents = new ArrayList<>(ACTIVE_AGENTS.values());
        agents.sort(Comparator.comparing(a -> a.agentId));
        for (int i = 0; i < agents.size(); i++) {
            AgentInfo a = agents.get(i);
            AgentSnapshot snap = a.snapshot();
            if (i > 0) sb.append(',');
            sb.append("{")
              .append("\"id\":\"").append(escapeJson(a.agentId)).append("\",")
              .append("\"hostname\":\"").append(escapeJson(a.hostname)).append("\",")
              .append("\"inactive\":").append(a.isInactive).append(",")
              .append("\"samples\":").append(snap.sampleCount).append(",")
              .append("\"avgCpu\":").append(formatDouble(snap.avgCpu)).append(",")
              .append("\"avgMemory\":").append(formatDouble(snap.avgMemory)).append(",")
              .append("\"lastUpdated\":").append(snap.lastUpdated)
              .append("}");
        }
        sb.append("],\"logs\":[");
        List<String> logs = new ArrayList<>(RECENT_LOGS);
        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String formatDouble(double v) { return String.format(Locale.US, "%.2f", v); }

    private static String escapeJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void logInfo(String msg) {
        System.out.println(msg); RECENT_LOGS.addLast(msg);
        while (RECENT_LOGS.size() > MAX_RECENT_LOGS) RECENT_LOGS.pollFirst();
        broadcastEvent("log", "{\"message\":\"" + escapeJson(msg) + "\"}");
    }

    private static void logError(String msg) {
        System.err.println(msg); RECENT_LOGS.addLast("ERROR: " + msg);
        while (RECENT_LOGS.size() > MAX_RECENT_LOGS) RECENT_LOGS.pollFirst();
        broadcastEvent("log", "{\"message\":\"" + escapeJson("ERROR: " + msg) + "\"}");
    }

    private static void startPeriodicStatsTask() {
        STATS_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                for (AgentInfo info : ACTIVE_AGENTS.values()) {
                    if (!info.isInactive && (now - info.lastUpdated) > 3000) {
                        info.isInactive = true;
                        logInfo("[INACTIF] Client #" + info.agentId + " has not sent a REPORT in 3T seconds");
                        broadcastEvent("inactivity", "{\"agentId\":\"" + escapeJson(info.agentId) + "\"}");
                    }
                }
                broadcastState();
            } catch (Exception e) { logError("Stats task error: " + e.getMessage()); }
        }, STATS_PERIOD_SECONDS, STATS_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final int tempId;
        ClientHandler(Socket s, int id) { this.socket = s; this.tempId = id; }

        @Override
        public void run() {
            String currentAgentId = null;
            try (Socket s = socket;
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
                out.println("MRCSS Server 1.0 Ready");
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length == 0) continue;
                    String cmd = p[0].toUpperCase();
                    if ("HELLO".equals(cmd) && p.length >= 3) {
                        currentAgentId = p[1];
                        ACTIVE_AGENTS.put(currentAgentId, new AgentInfo(currentAgentId, p[2]));
                        logInfo("Agent registered: " + currentAgentId + " (" + p[2] + ")");
                        out.println("OK"); broadcastState();
                    } else if ("REPORT".equals(cmd) && p.length >= 5) {
                        String id = p[1];
                        if (currentAgentId == null || !currentAgentId.equals(id)) {
                            out.println("ERROR Unregistered Agent"); continue;
                        }
                        try {
                            long ts = Long.parseLong(p[2]);
                            double cpu = Double.parseDouble(p[3]);
                            double ram = Double.parseDouble(p[4]);
                            if (cpu < 0 || cpu > 100 || ram < 0) {
                                out.println("ERROR Invalid Metrics"); continue;
                            }
                            AgentInfo info = ACTIVE_AGENTS.get(id);
                            if (info != null) { info.addSample(ts, cpu, ram); info.isInactive = false; out.println("OK"); }
                        } catch (Exception e) { out.println("ERROR Malformed fields"); }
                    } else if ("BYE".equals(cmd)) {
                        if (currentAgentId != null) ACTIVE_AGENTS.remove(currentAgentId);
                        out.println("OK"); broadcastState(); break;
                    } else {
                        out.println("ERROR Unknown Command");
                    }
                }
            } catch (IOException e) { logError("Connection error: " + e.getMessage()); }
            finally { if (currentAgentId != null) { ACTIVE_AGENTS.remove(currentAgentId); broadcastState(); } }
        }
    }

    private static class AgentInfo {
        final String agentId, hostname;
        final List<MetricSample> samples = new ArrayList<>();
        long lastUpdated = System.currentTimeMillis();
        boolean isInactive = false;
        AgentInfo(String id, String host) { this.agentId = id; this.hostname = host; }
        synchronized void addSample(long ts, double c, double m) {
            samples.add(new MetricSample(ts, c, m)); lastUpdated = System.currentTimeMillis();
        }
        synchronized AgentSnapshot snapshot() {
            double cs = 0, ms = 0;
            for (MetricSample s : samples) { cs += s.cpu; ms += s.memory; }
            return new AgentSnapshot(cs / Math.max(1, samples.size()), ms / Math.max(1, samples.size()), samples.size(), lastUpdated);
        }
    }

    private static class MetricSample {
        final long timestamp; final double cpu, memory;
        MetricSample(long ts, double c, double m) { this.timestamp = ts; this.cpu = c; this.memory = m; }
    }

    private static class AgentSnapshot {
        final double avgCpu, avgMemory; final long sampleCount, lastUpdated;
        AgentSnapshot(double ac, double am, long sc, long lu) {
            this.avgCpu = ac; this.avgMemory = am; this.sampleCount = sc; this.lastUpdated = lu;
        }
    }

    private static class SseClient {
        private final OutputStream out;
        SseClient(OutputStream o) { this.out = o; }
        synchronized void sendEvent(String name, String data) throws IOException {
            out.write(("event: " + name + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        void closeSilently() { try { out.close(); } catch (IOException ignored) {} }
    }
}