package Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {
    private static final int PORT = 1234;
    private static final int STATS_PERIOD_SECONDS = 10;
    private static final AtomicInteger CLIENT_ID_COUNTER = new AtomicInteger(1);
    private static final Map<Integer, AgentInfo> ACTIVE_AGENTS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService STATS_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) throws IOException {
        startPeriodicStatsTask();

        try (ServerSocket ss = new ServerSocket(PORT)) {
            System.out.println("Server is running on port " + PORT + "...");

            while (!ss.isClosed()) {
                try {
                    System.out.println("Waiting for a connection...");
                    Socket s = ss.accept();

                    int clientId = CLIENT_ID_COUNTER.getAndIncrement();
                    AgentInfo info = new AgentInfo();
                    ACTIVE_AGENTS.put(clientId, info);

                    Thread clientThread = new Thread(new ClientHandler(s, clientId), "client-" + clientId);
                    clientThread.start();
                } catch (IOException e) {
                    System.err.println("Accept error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            STATS_EXECUTOR.shutdownNow();
            ACTIVE_AGENTS.clear();
            System.out.println("Server shutdown: cleared in-memory agent metrics.");
        }
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
                        System.out.printf(
                            Locale.US,
                            "[Client #%d] samples=%d | avgCpu=%.2f | avgMemory=%.2f%n",
                            clientId,
                            snapshot.sampleCount,
                            snapshot.avgCpu,
                            snapshot.avgMemory
                        );

                        globalCpuSum += snapshot.cpuSum;
                        globalMemSum += snapshot.memorySum;
                        globalSampleCount += snapshot.sampleCount;
                    } else {
                        System.out.println("[Client #" + clientId + "] samples=0 | avgCpu=N/A | avgMemory=N/A");
                    }
                }

                double globalAvgCpu = globalSampleCount == 0 ? 0.0 : globalCpuSum / globalSampleCount;
                double globalAvgMemory = globalSampleCount == 0 ? 0.0 : globalMemSum / globalSampleCount;

                System.out.printf(
                    Locale.US,
                    "[Stats] Active agents=%d | globalAvgCpu=%.2f | globalAvgMemory=%.2f | totalSamples=%d%n",
                    activeCount,
                    globalAvgCpu,
                    globalAvgMemory,
                    globalSampleCount
                );
            } catch (Exception e) {
                System.err.println("Stats task error: " + e.getMessage());
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
            System.out.println("Client #" + clientId + " connected from " + socket.getRemoteSocketAddress());

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

                    if ("/quit".equalsIgnoreCase(trimmed)) {
                        out.println("Disconnected by client request.");
                        break;
                    }

                    System.out.println("Client #" + clientId + " says: " + request);

                    if (info != null && tryUpdateMetrics(info, trimmed)) {
                        // Metrics are processed silently to avoid flooding responses every second.
                    } else {
                        out.println("Message received from client #" + clientId + ".");
                    }
                }
            } catch (IOException e) {
                System.err.println("Error handling client #" + clientId + ": " + e.getMessage());
            } finally {
                ACTIVE_AGENTS.remove(clientId);
                System.out.println("Client #" + clientId + " disconnected.");
            }
        }
    }

    private static class AgentInfo {
        private final List<MetricSample> samples = new ArrayList<>();

        synchronized void addSample(double cpu, double memory) {
            samples.add(new MetricSample(cpu, memory));
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
            return new AgentSnapshot(cpuSum, memorySum, sampleCount, avgCpu, avgMemory);
        }
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

        AgentSnapshot(double cpuSum, double memorySum, long sampleCount, double avgCpu, double avgMemory) {
            this.cpuSum = cpuSum;
            this.memorySum = memorySum;
            this.sampleCount = sampleCount;
            this.avgCpu = avgCpu;
            this.avgMemory = avgMemory;
        }
    }
}