const form = document.querySelector("#controls");
const el = id => document.getElementById(id);
const card = document.querySelector(".estimate");
const fields = form.elements;
let version = 0;
let timer;

function schedule() {
  clearTimeout(timer);
  const current = ++version;
  const query = new URLSearchParams(new FormData(form));
  el("gapVal").textContent = fields.gap.value + " min";
  el("elapsedVal").textContent = fields.elapsed.value + " min";
  el("clock").textContent = fields.hour.selectedOptions[0].text;
  el("stage").textContent = fields.stage.selectedOptions[0].text;
  el("wait").textContent = "Updating…";
  el("detail").textContent = "Computing the wait range.";
  card.dataset.state = "loading";
  el("history").replaceChildren();
  el("evidence").textContent = "";
  timer = setTimeout(async () => {
    try {
      const response = await fetch("/estimate?" + query);
      if (!response.ok) throw new Error("request failed");
      const data = await response.json();
      if (current !== version) return;
      card.dataset.state = data.status;
      const available = data.status === "available";
      el("wait").textContent = available
        ? data.low.toFixed(1) + "–" + data.high.toFixed(1) + " min" : "No estimate";
      el("detail").textContent = available
        ? "Median " + data.median.toFixed(1) + " min · 80% prediction range" : data.reason;
      el("history").replaceChildren(...data.arrivals.map(time => {
        const item = document.createElement("span");
        item.textContent = time;
        return item;
      }));
      el("evidence").textContent = data.recent + " of 3 simulated arrivals within the last 30 minutes.";
    } catch {
      if (current !== version) return;
      el("wait").textContent = "Server unavailable";
      el("detail").textContent = "Restart the local server, then press Reset.";
    }
  }, 80);
}
form.addEventListener("input", schedule);
form.addEventListener("reset", () => queueMicrotask(schedule));
el("stale").addEventListener("click", () => { fields.elapsed.value = "31"; schedule(); });
schedule();
