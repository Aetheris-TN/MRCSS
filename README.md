# 🧠 End-to-End Telemetry Pipeline

A real-time telemetry system with ingestion, processing, and visualization layers.

---

## 🏗️ Architecture Overview

```
Agent Layer (Clients)       →  generates telemetry
Processing Layer (Server)   →  ingestion + aggregation
Presentation Layer          →  visualization
```

---

## 🔄 Data Flow

### 📡 1. TCP Ingestion

- Clients connect to port `1234`
- Send metrics every second:

```
METRICS <cpu> <memory>
```

### ⚙️ 2. Server Processing

- One thread per client
- Server:
  - Parses incoming data
  - Stores samples
  - Tracks active clients (in-memory)

### 📊 3. Aggregation (Every 10s)

- Per-client averages
- Global weighted averages

### 🌐 4. HTTP + Real-Time Layer (port `8080`)

| Endpoint      | Description       |
|---------------|-------------------|
| `/`           | Dashboard UI      |
| `/api/state`  | JSON snapshot     |
| `/api/events` | SSE stream        |

### 🖥️ 5. Dashboard Behavior

```
Sidebar → [Stats] ...
Main    → 3 rotating clients [Client #x] ...
```

> ℹ️ Uses **SSE** (push) for real-time updates and **polling** (pull) as fallback.

---

## 🧩 Conceptual Model

<img width="495" height="384" alt="image" src="https://github.com/user-attachments/assets/ca18aff5-8f1f-4527-9667-0b0e2eebc5c2" />

### 🧠 Design Principles

#### 🔹 Separation of Concerns
- TCP → ingestion
- HTTP → delivery
- Frontend → rendering

#### 🔹 Resilience (Push + Pull)
- SSE → real-time
- Polling → fallback

#### 🔹 In-Memory State

> ⚠️ Data is ephemeral (reset on shutdown)

#### 🔹 Thread-per-Client
- Simple concurrency
- Fault isolation

#### 🔹 Deterministic UI
- Fixed format output
- Round-robin display

---

## 📂 File-by-File Breakdown

### 1️⃣ Server Core — `Server.java`

**🎯 Role**

Central runtime (collector + aggregator + web server)

**🔧 Responsibilities**

- **TCP Server**
  - Port: `1234`
  - One thread per client
  - Unique IDs via `AtomicInteger`

- **Client State**
  - `ConcurrentHashMap<Integer, AgentInfo>`
  - `AgentInfo`: samples + metadata
  - `MetricSample`: cpu / memory

- **Aggregation Engine**
  - Runs every 10s
  - Computes:
    - per-client stats
    - global weighted averages

- **HTTP Server**
  - Port: `8080`
  - Serves:
    - `index.html`
    - `styles.css`
    - `script.js`

- **API**
  - `/api/state` → JSON snapshot
  - `/api/events` → SSE stream

- **SSE System**
  - `SSE_CLIENTS`
  - `broadcastState()`

- **Shutdown Safety**
  - Ensures scheduler stops, HTTP server stops, memory cleared, and ports released

**🧠 Key Concepts**

- `ConcurrentHashMap` → no global locks
- Snapshot JSON → avoids race conditions
- Eventual consistency → UI derived from state

---

### 2️⃣ Client Agent — `Client.java`

**🎯 Role**

Telemetry producer + interactive client

**🔧 Responsibilities**

- **Connection**
  - `localhost:1234`

- **Metrics Emission**
  - `METRICS %.2f %.2f`
  - CPU → `OperatingSystemMXBean`
  - Memory → JVM heap (MB)

- **Interactive Mode**
  - User input supported
  - `/quit` → exit

- **Concurrency**
  - Thread 1 → send data
  - Thread 2 → read server
  - `AtomicBoolean` → lifecycle control

**🧠 Key Concept**

Producer-Consumer Pattern:
- Scheduler → producer
- stdin → producer
- reader thread → consumer

---

### 3️⃣ Dashboard — `index.html`

**🎯 Role**

Static UI structure

**🔧 Responsibilities**

- Layout:
  - Header
  - Sidebar
  - Main
  - Footer
- Data anchors:
  - `#global-stats-line`
  - `#slot-0/1/2-*`

**🧠 Concept**

DOM-as-template → Static structure + dynamic JS injection

---

### 4️⃣ Styling — `styles.css`

**🎯 Role**

Visual system

**🔧 Responsibilities**

- Theme (CSS variables)
- Layout (grid)
- Responsive design
- Monospace logs

**🧠 Concept**

- Design tokens → consistency
- Component styling → precision

---

### 5️⃣ Frontend Logic — `script.js`

**🎯 Role**

State sync + rendering

**🔧 Responsibilities**

- **State**
  - `activeClients`
  - `rotationStart`

- **Rendering**

  Client line:
  ```
  [Client #x] samples=... | avgCpu=... | avgMemory=...
  ```

  Global stats:
  ```
  [Stats] Active agents=... | globalAvgCpu=... | globalAvgMemory=... | totalSamples=...
  ```

- **Transport**
  - SSE → `/api/events`
  - Polling → `/api/state`

- **Rotation**
  - Every 3 seconds
  - Max 3 clients visible

**🧠 Concepts**

- Client-side projection
- Graceful degradation

---

### 6️⃣ Orchestration — `run-all.bat`

**🎯 Role**

One-click execution

**🔧 Responsibilities**

- Compile code
- Kill ports `8080` / `1234`
- Start server
- Open browser
- Launch clients

**🧠 Concept**

Improves developer experience and prevents startup errors

---

## 🔗 System Integration

<img width="861" height="602" alt="image" src="https://github.com/user-attachments/assets/b5c2fb06-edc8-4ad2-a131-e9f607c6ef1f" />



### ⚠️ Java Execution Note

Because of Java packages, run:

```bash
java Server.Server
java Client.Client
```

❌ Do **NOT** run:

```bash
java Server.java
java Client.java
```

---

## ✅ Summary

This project demonstrates:

- Real-time TCP ingestion
- Concurrent processing
- Periodic aggregation
- SSE streaming + polling fallback
- Clean backend/frontend separation
