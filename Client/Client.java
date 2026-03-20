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

public class Client {
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
                System.out.println("Server says: " + welcome);
            }

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
                String metricsLine = String.format(Locale.US, "METRICS %.2f %.2f", cpu, memory);

                synchronized (out) {
                    out.println(metricsLine);
                }
            }, 0, 1, TimeUnit.SECONDS);

            while (true) {
                System.out.print("Enter message for server: ");
                String message = consoleReader.readLine();
                if (message == null) {
                    synchronized (out) {
                        out.println("/quit");
                    }
                    break;
                }

                synchronized (out) {
                    out.println(message);
                }

                if ("/quit".equalsIgnoreCase(message.trim())) {
                    break;
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
