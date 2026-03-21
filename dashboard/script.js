function formatTime(date) {
  return date.toLocaleTimeString("en-US", {
    hour: "numeric",
    minute: "2-digit",
    second: "2-digit",
    hour12: true,
  });
}

const slotIds = [0, 1, 2];
let activeClients = [];
let rotationStart = 0;
const API_BASE = window.location.protocol === "file:" ? "http://localhost:8080" : "";

function updateTimestamps() {
  const now = new Date();
  const main = document.getElementById("clock-main");
  const mini = document.getElementById("clock-mini");

  if (main) {
    main.textContent = formatTime(now);
  }

  if (mini) {
    mini.textContent = "Updated: " + formatTime(now);
  }
}

function formatUpdated(epochMs) {
  if (!epochMs) {
    return "Updated: Waiting...";
  }
  return "Updated: " + formatTime(new Date(epochMs));
}

function setSlot(slotIndex, client) {
  const updatedEl = document.getElementById("slot-" + slotIndex + "-updated");
  const roleEl = document.getElementById("slot-" + slotIndex + "-role");
  const titleEl = document.getElementById("slot-" + slotIndex + "-title");
  const line1El = document.getElementById("slot-" + slotIndex + "-line1");
  const line2El = document.getElementById("slot-" + slotIndex + "-line2");
  const badgeEl = document.getElementById("slot-" + slotIndex + "-badge");
  const cardEl = updatedEl.closest('.user-group').querySelector('.user-card');

  if (!updatedEl || !roleEl || !titleEl || !line1El || !line2El || !badgeEl || !cardEl) {
    return;
  }

  if (!client) {
    updatedEl.textContent = "Updated: Waiting...";
    roleEl.textContent = "Waiting";
    titleEl.textContent = "Slot " + (slotIndex + 1) + " - Waiting for client";
    line1El.textContent = "[Client #0] samples=0 | avgCpu=0.00 | avgMemory=0.00";
    line2El.textContent = "";
    cardEl.classList.remove("inactive");
    return;
  }

  const isInactive = !!client.inactive;
  if (isInactive) {
    cardEl.classList.add("inactive");
  } else {
    cardEl.classList.remove("inactive");
  }

  updatedEl.textContent = formatUpdated(client.lastUpdated);
  roleEl.textContent = "Client #" + client.id;
  titleEl.textContent = "Round Robin Slot " + (slotIndex + 1);
  line1El.textContent =
    "[Client #" + client.id.substring(0, 8) + "...] samples=" + client.samples +
    " | avgCpu=" + client.avgCpu.toFixed(2) +
    " | avgMemory=" + client.avgMemory.toFixed(2);
  line2El.textContent = "";
}

function renderSlots() {
  if (activeClients.length === 0) {
    slotIds.forEach((slot) => setSlot(slot, null));
    return;
  }

  for (let i = 0; i < slotIds.length; i++) {
    const clientIndex = (rotationStart + i) % activeClients.length;
    setSlot(i, activeClients[clientIndex]);
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderGlobalStats() {
  const globalLine = document.getElementById("global-stats-line");
  if (!globalLine) {
    return;
  }

  let totalSamples = 0;
  let weightedCpuSum = 0;
  let weightedMemorySum = 0;

  for (const client of activeClients) {
    const samples = Number(client.samples || 0);
    const avgCpu = Number(client.avgCpu || 0);
    const avgMemory = Number(client.avgMemory || 0);

    totalSamples += samples;
    weightedCpuSum += avgCpu * samples;
    weightedMemorySum += avgMemory * samples;
  }

  const globalAvgCpu = totalSamples > 0 ? weightedCpuSum / totalSamples : 0;
  const globalAvgMemory = totalSamples > 0 ? weightedMemorySum / totalSamples : 0;

  globalLine.textContent =
    "[Stats] Active agents=" + activeClients.length +
    " | globalAvgCpu=" + globalAvgCpu.toFixed(2) +
    " | globalAvgMemory=" + globalAvgMemory.toFixed(2) +
    " | totalSamples=" + totalSamples;
}

function applyState(payload) {
  activeClients = Array.isArray(payload.activeClients) ? payload.activeClients : [];

  if (rotationStart >= activeClients.length) {
    rotationStart = 0;
  }

  renderSlots();
  renderGlobalStats();

  const countEl = document.getElementById("agent-count");
  if (countEl) {
    countEl.textContent = activeClients.length + " agent" + (activeClients.length === 1 ? "" : "s") + " connected";
  }
}

function startEventStream() {
  const eventsUrl = API_BASE + "/api/events";
  if (!window.EventSource) {
    startPollingFallback();
    return;
  }

  const source = new EventSource(eventsUrl);
  let errorCount = 0;

  source.addEventListener("state", (event) => {
    errorCount = 0;
    try {
      applyState(JSON.parse(event.data));
    } catch (error) {
      console.error("Failed to parse state event", error);
    }
  });

  source.addEventListener("log", (event) => {
    // No-op by design: dashboard displays only mapped stats/client lines.
  });

  source.onerror = () => {
    errorCount += 1;
    source.close();

    if (errorCount >= 3) {
      startPollingFallback();
      return;
    }

    setTimeout(startEventStream, 1500);
  };
}

function startPollingFallback() {
  const stateUrl = API_BASE + "/api/state";
  const load = () => {
    fetch(stateUrl)
      .then((response) => response.json())
      .then((payload) => applyState(payload))
      .catch((error) => console.error("Polling error", error));
  };

  load();
  setInterval(load, 1500);
}

function animateMiniLine() {
  const path = document.querySelector(".mini-line path");
  if (!path) {
    return;
  }

  let t = 0;
  setInterval(() => {
    t += 0.18;
    const points = [];

    for (let x = 0; x <= 240; x += 20) {
      const y = 40 + Math.sin((x / 24) + t) * 14 + Math.sin((x / 40) + (t * 0.7)) * 7;
      points.push([x, y]);
    }

    let d = "M" + points[0][0] + " " + points[0][1];
    for (let i = 1; i < points.length; i++) {
      const prev = points[i - 1];
      const curr = points[i];
      const cx = (prev[0] + curr[0]) / 2;
      d += " Q " + prev[0] + " " + prev[1] + " " + cx + " " + ((prev[1] + curr[1]) / 2);
    }
    d += " T 240 " + points[points.length - 1][1];

    path.setAttribute("d", d);
  }, 900);
}

updateTimestamps();
setInterval(updateTimestamps, 1000);
animateMiniLine();
startPollingFallback();
setInterval(() => {
  if (activeClients.length > 3) {
    rotationStart = (rotationStart + 1) % activeClients.length;
    renderSlots();
  }
}, 3000);
startEventStream();

const downloadBtn = document.getElementById("download-csv");
if (downloadBtn) {
  downloadBtn.addEventListener("click", () => {
    window.location.href = API_BASE + "/api/export";
  });
}

const spawnBtn = document.getElementById("spawn-agent");
if (spawnBtn) {
  spawnBtn.addEventListener("click", () => {
    fetch(API_BASE + "/api/spawn-agent")
      .then(r => r.json())
      .then(data => {
        if (data.status === "error") {
          alert("Failed to spawn agent: " + data.message);
        }
      })
      .catch(e => console.error("Spawn error", e));
  });
}
