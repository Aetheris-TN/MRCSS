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
    private static final Map<Integer, AgentInfo> ACTIVE_AGENTS = new ConcurrentHashMap<>();
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
            logInfo("Dashboard directory resolved to: " + DASHBOARD_DIR);

            while (!ss.isClosed()) {
                try {
                    logInfo("Waiting for a connection...");
                    Socket s = ss.accept();

                    int clientId = CLIENT_ID_COUNTER.getAndIncrement();
                    AgentInfo info = new AgentInfo();
                    info.setLastMessage("Connected");
                    ACTIVE_AGENTS.put(clientId, info);
                    broadcastState();

                    Thread clientThread = new Thread(new ClientHandler(s, clientId), "client-" + clientId);
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

        try {
            STATS_EXECUTOR.shutdownNow();
        } catch (Exception ignored) {
            // Ignore errors while shutting down scheduler.
        }

        try {
            if (dashboardWebServer != null) {
                dashboardWebServer.stop(0);
            }
        } catch (Exception ignored) {
            // Ignore errors while stopping embedded web server.
        }

        for (SseClient client : SSE_CLIENTS) {
            client.closeSilently();
        }
        SSE_CLIENTS.clear();
        ACTIVE_AGENTS.clear();
        RECENT_LOGS.clear();

        System.out.println("Server shutdown completed (" + origin + "): port 8080 closed and in-memory data cleared.");
    }

    private static void startDashboardWebServer() {
        try {
            dashboardWebServer = HttpServer.create(new InetSocketAddress(DASHBOARD_PORT), 0);
            dashboardWebServer.createContext("/", exchange -> serveStaticFile(exchange, "index.html", "text/html; charset=utf-8"));
            dashboardWebServer.createContext("/styles.css", exchange -> serveStaticFile(exchange, "styles.css", "text/css; charset=utf-8"));
            dashboardWebServer.createContext("/script.js", exchange -> serveStaticFile(exchange, "script.js", "application/javascript; charset=utf-8"));
            dashboardWebServer.createContext("/api/state", Server::serveStateJson);
            dashboardWebServer.createContext("/api/events", Server::handleSse);
            dashboardWebServer.setExecutor(Executors.newCachedThreadPool());
            dashboardWebServer.start();
        } catch (IOException e) {
            logError("Unable to start dashboard web server: " + e.getMessage());
        }
    }

    private static void serveStaticFile(HttpExchange exchange, String fileName, String contentType) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            sendMethodNotAllowed(exchange);
            return;
        }

        Path filePath = DASHBOARD_DIR.resolve(fileName).normalize();
        if (!Files.exists(filePath)) {
            byte[] body = "File not found".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            return;
        }

        byte[] bytes = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Path findDashboardDir() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path cursor = cwd;

        for (int i = 0; i < 8 && cursor != null; i++) {
            Path candidate = cursor.resolve("dashboard");
            if (Files.isDirectory(candidate) && Files.exists(candidate.resolve("index.html"))) {
                return candidate;
            }
            cursor = cursor.getParent();
        }

        // Fallback to project-like relative path from current directory.
        return cwd.resolve("dashboard").normalize();
    }

    private static void serveStateJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        byte[] body = buildStateJson().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void handleSse(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        SseClient client = new SseClient(os);
        SSE_CLIENTS.add(client);
        client.sendEvent("state", buildStateJson());
    }

    private static void broadcastState() {
        broadcastEvent("state", buildStateJson());
    }

    private static void broadcastEvent(String eventName, String payloadJson) {
        for (SseClient client : SSE_CLIENTS) {
            try {
                client.sendEvent(eventName, payloadJson);
            } catch (IOException e) {
                client.closeSilently();
                SSE_CLIENTS.remove(client);
            }
        }
    }

    private static String buildStateJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"activeClients\":[");

        List<Map.Entry<Integer, AgentInfo>> entries = new ArrayList<>(ACTIVE_AGENTS.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getKey));

        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Integer, AgentInfo> entry = entries.get(i);
            int clientId = entry.getKey();
            AgentSnapshot snapshot = entry.getValue().snapshot();

            if (i > 0) {
                sb.append(',');
            }

            sb.append("{")
                .append("\"id\":").append(clientId).append(',')
                .append("\"samples\":").append(snapshot.sampleCount).append(',')
                .append("\"avgCpu\":").append(formatDouble(snapshot.avgCpu)).append(',')
                .append("\"avgMemory\":").append(formatDouble(snapshot.avgMemory)).append(',')
                .append("\"lastMessage\":\"").append(escapeJson(snapshot.lastMessage)).append("\",")
                .append("\"lastUpdated\":").append(snapshot.lastUpdated)
                .append("}");
        }

        sb.append("],\"logs\":[");

        List<String> logs = new ArrayList<>(RECENT_LOGS);
        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
        }

        sb.append("]}");
        return sb.toString();
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        byte[] body = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(405, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void logInfo(String message) {
        System.out.println(message);
        RECENT_LOGS.addLast(message);
        while (RECENT_LOGS.size() > MAX_RECENT_LOGS) {
            RECENT_LOGS.pollFirst();
        }
        broadcastEvent("log", "{\"message\":\"" + escapeJson(message) + "\"}");
    }

    private static void logError(String message) {
        System.err.println(message);
        RECENT_LOGS.addLast("ERROR: " + message);
        while (RECENT_LOGS.size() > MAX_RECENT_LOGS) {
            RECENT_LOGS.pollFirst();
        }
        broadcastEvent("log", "{\"message\":\"" + escapeJson("ERROR: " + message) + "\"}");
    }

    private static void startPeriodicStatsTask() {
        STATS_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                int activeCount = ACTIVE_AGENTS.size();
                double globalCpuSum = 0.0;
                double globalMemSum = 0.0;
                long globalSampleCount = 0;

                for (Map.Entry<Integer, AgentInfo> entry : ACTIVE_AGENTS.entrySet()) {
                    int clientId = entry.getKey();
                    AgentSnapshot snapshot = entry.getValue().snapshot();

                    if (snapshot.sampleCount > 0) {
                        logInfo(String.format(
                            Locale.US,
                            "[Client #%d] samples=%d | avgCpu=%.2f | avgMemory=%.2f%n",
                            clientId,
                            snapshot.sampleCount,
                            snapshot.avgCpu,
                            snapshot.avgMemory
                        ).trim());

                        globalCpuSum += snapshot.cpuSum;
                        globalMemSum += snapshot.memorySum;
                        globalSampleCount += snapshot.sampleCount;
                    } else {
                        logInfo("[Client #" + clientId + "] samples=0 | avgCpu=N/A | avgMemory=N/A");
                    }
                }

                double globalAvgCpu = globalSampleCount == 0 ? 0.0 : globalCpuSum / globalSampleCount;
                double globalAvgMemory = globalSampleCount == 0 ? 0.0 : globalMemSum / globalSampleCount;

                logInfo(String.format(
                    Locale.US,
                    "[Stats] Active agents=%d | globalAvgCpu=%.2f | globalAvgMemory=%.2f | totalSamples=%d%n",
                    activeCount,
                    globalAvgCpu,
                    globalAvgMemory,
                    globalSampleCount
                ).trim());

                broadcastState();
            } catch (Exception e) {
                logError("Stats task error: " + e.getMessage());
            }
        }, STATS_PERIOD_SECONDS, STATS_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private static boolean tryUpdateMetrics(AgentInfo info, String message) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length != 3 || !"METRICS".equalsIgnoreCase(parts[0])) {
            return false;
        }

        try {
            double cpu = Double.parseDouble(parts[1]);
            double memory = Double.parseDouble(parts[2]);
            info.addSample(cpu, memory);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final int clientId;

        ClientHandler(Socket socket, int clientId) {
            this.socket = socket;
            this.clientId = clientId;
        }

        @Override
        public void run() {
            logInfo("Client #" + clientId + " connected from " + socket.getRemoteSocketAddress());

            try (
                Socket s = socket;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true)
            ) {
                AgentInfo info = ACTIVE_AGENTS.get(clientId);
                out.println("Connected. Your client ID is #" + clientId);

                String request;
                while ((request = in.readLine()) != null) {
                    String trimmed = request.trim();
                    if (info != null) {
                        info.setLastMessage(trimmed);
                    }

                    if ("/quit".equalsIgnoreCase(trimmed)) {
                        out.println("Disconnected by client request.");
                        broadcastState();
                        break;
                    }

                    logInfo("Client #" + clientId + " says: " + request);

                    if (info != null && tryUpdateMetrics(info, trimmed)) {
                        // Metrics are processed silently to avoid flooding responses every second.
                        broadcastState();
                    } else {
                        out.println("Message received from client #" + clientId + ".");
                        broadcastState();
                    }
                }
            } catch (IOException e) {
                logError("Error handling client #" + clientId + ": " + e.getMessage());
            } finally {
                ACTIVE_AGENTS.remove(clientId);
                logInfo("Client #" + clientId + " disconnected.");
                broadcastState();
            }
        }
    }

    private static class AgentInfo {
        private final List<MetricSample> samples = new ArrayList<>();

        synchronized void addSample(double cpu, double memory) {
            samples.add(new MetricSample(cpu, memory));
            lastUpdated = System.currentTimeMillis();
        }

        synchronized void setLastMessage(String message) {
            lastMessage = message;
            lastUpdated = System.currentTimeMillis();
        }

        synchronized AgentSnapshot snapshot() {
            double cpuSum = 0.0;
            double memorySum = 0.0;
            long sampleCount = samples.size();

            for (MetricSample sample : samples) {
                cpuSum += sample.cpu;
                memorySum += sample.memory;
            }

            double avgCpu = sampleCount == 0 ? 0.0 : cpuSum / sampleCount;
            double avgMemory = sampleCount == 0 ? 0.0 : memorySum / sampleCount;
            return new AgentSnapshot(cpuSum, memorySum, sampleCount, avgCpu, avgMemory, lastMessage, lastUpdated);
        }

        private String lastMessage = "";
        private long lastUpdated = System.currentTimeMillis();
    }

    private static class MetricSample {
        private final double cpu;
        private final double memory;

        MetricSample(double cpu, double memory) {
            this.cpu = cpu;
            this.memory = memory;
        }
    }

    private static class AgentSnapshot {
        private final double cpuSum;
        private final double memorySum;
        private final long sampleCount;
        private final double avgCpu;
        private final double avgMemory;
        private final String lastMessage;
        private final long lastUpdated;

        AgentSnapshot(double cpuSum, double memorySum, long sampleCount, double avgCpu, double avgMemory,
            String lastMessage, long lastUpdated) {
            this.cpuSum = cpuSum;
            this.memorySum = memorySum;
            this.sampleCount = sampleCount;
            this.avgCpu = avgCpu;
            this.avgMemory = avgMemory;
            this.lastMessage = lastMessage;
            this.lastUpdated = lastUpdated;
        }
    }

    private static class SseClient {
        private final OutputStream output;

        SseClient(OutputStream output) {
            this.output = output;
        }

        synchronized void sendEvent(String eventName, String dataJson) throws IOException {
            String eventPayload = "event: " + eventName + "\n" + "data: " + dataJson + "\n\n";
            output.write(eventPayload.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        synchronized void closeSilently() {
            try {
                output.close();
            } catch (IOException ignored) {
                // Ignore close errors for stale SSE clients.
            }
        }
    }
}