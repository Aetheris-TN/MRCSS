# MRCSS (Monitoring & Report Control System)

MRCSS is a distributed system for monitoring CPU and RAM usage across multiple agents. It consists of a Java Server, multiple Java Client agents, and a web-based Dashboard.

## Compilation

To compile the project, run the following command from the project root:

```bash
javac Server/Server.java Client/Client.java
```

## Running the System

1.  **Start the Server**:
    ```bash
    java Server.Server
    ```
    The server listens for agents on port `1234` and serves the dashboard on port `8080`.

2.  **Start the Agents**:
    ```bash
    java Client.Client
    ```
    Each agent generates a unique UUID on startup and starts reporting metrics every second.

## Protocol Specification

The system uses a custom text-based protocol over TCP.

### Client-to-Server Commands

| Command | Format | Description |
| :--- | :--- | :--- |
| **HELLO** | `HELLO <agent_id> <hostname>` | Sent immediately upon connection to register the agent. |
| **REPORT** | `REPORT <agent_id> <timestamp> <cpu_pct> <ram_mb>` | Sent every second to report current system metrics. |
| **BYE** | `BYE <agent_id>` | Sent before graceful disconnection. |

### Server Responses

| Response | Description |
| :--- | :--- |
| **OK** | Command processed successfully. |
| **ERROR <msg>** | Command failed due to malformed input, invalid metrics, or registration issues. |

## Dashboard

The dashboard is accessible at: [http://localhost:8080](http://localhost:8080)

Features:
- **Live Connection Counter**: Shows "N agents connected" in the header.
- **Inactivity Detection**: Agents that don't report for 3 seconds are marked with a red pulsing badge.
- **CSV Export**: Click "Download CSV" to get a history of all collected metrics.
- **Emerald Theme**: Consistent and modern styling.

## Test Checklist (Mandatory Scenarios)

- [ ] **Scenario 1: Protocol Compliance** - Verify HELLO/REPORT/BYE flow and OK/ERROR responses.
- [ ] **Scenario 2: Unique Identification** - Verify each client has a unique UUID and hostname.
- [ ] **Scenario 3: Strict Validation** - Verify server rejects `cpu_pct` > 100 or `< 0`, and non-numeric fields.
- [ ] **Scenario 4: Inactivity Detection** - Verify red pulsing badge appears after 3 seconds of no reports.
- [ ] **Scenario 5: Data Persistence & Export** - Verify CSV download contains correct history for all agents.
- [ ] **Scenario 6: Robustness** - Verify the server does not crash when receiving random or malformed text.
