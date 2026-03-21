package Client;

import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.net.InetAddress;

public class Client {
    private static final String agentId = UUID.randomUUID().toString();
    private static final String hostname = getHostname();

    private static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
    public static void main(String[] args) {
        BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
        AtomicBoolean running = new AtomicBoolean(true);

        try (Socket socket = new Socket("localhost", 1234);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("Connected to server.");
            System.out.println("Client now sends CPU and memory every second automatically.");
            System.out.println("You can still type messages manually.");
            System.out.println("Type /quit to disconnect.");

            String welcome = in.readLine();
            if (welcome != null) {
                System.out.println("Server (init): " + welcome);
            }

            // Protocol: HELLO <agent_id> <hostname>
            out.println("HELLO " + agentId + " " + hostname);

            Thread serverReaderThread = new Thread(() -> {
                try {
                    String serverMessage;
                    while (running.get() && (serverMessage = in.readLine()) != null) {
                        System.out.println("Server says: " + serverMessage);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("Connection read error: " + e.getMessage());
                    }
                } finally {
                    running.set(false);
                }
            }, "server-reader");
            serverReaderThread.setDaemon(true);
            serverReaderThread.start();

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                if (!running.get()) {
                    return;
                }

                double cpu = readCpuPercent();
                double memory = readUsedMemoryMb();
                long timestamp = System.currentTimeMillis() / 1000; // Protocol uses epoch seconds
                // Protocol: REPORT <agent_id> <timestamp> <cpu_pct> <ram_mb>
                String metricsLine = String.format(Locale.US, "REPORT %s %d %.2f %.2f", agentId, timestamp, cpu, memory);

                synchronized (out) {
                    out.println(metricsLine);
                }
            }, 0, 1, TimeUnit.SECONDS);

            while (true) {
                System.out.print("Enter command for server (type /quit to exit): ");
                String message = consoleReader.readLine();
                if (message == null || "/quit".equalsIgnoreCase(message.trim())) {
                    synchronized (out) {
                        out.println("BYE " + agentId);
                    }
                    break;
                }

                synchronized (out) {
                    out.println(message);
                }
            }

            running.set(false);
            scheduler.shutdownNow();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static double readCpuPercent() {
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        if (osBean == null) {
            return 0.0;
        }

        double load = osBean.getCpuLoad();
        if (load < 0) {
            return 0.0;
        }
        return load * 100.0;
    }

    private static double readUsedMemoryMb() {
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        return usedBytes / (1024.0 * 1024.0);
    }
}
