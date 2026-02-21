// ===== Constants =====
const STORAGE_KEY = "makimura_app_state_v1";
const MS_PER_DAY = 86_400_000;
const HISTORY_LIMIT = 365;
const WEATHER_REFRESH_MS = 15 * 60 * 1_000;

// Step sensor
const CADENCE_WINDOW_MS = 12_000;
const BRISK_CADENCE_SPM = 120.0;
const RUNNING_CADENCE_SPM = 150.0;
const MIN_STEP_GAP_MS = 200;
const MAX_STEP_GAP_MS = 3_000;

// Profile
const MAX_HEIGHT_CM = 220;
const MIN_HEIGHT_CM = 120;
const MIN_STRIDE_SCALE = 0.7;
const MAX_STRIDE_SCALE = 1.3;
const MIN_WEIGHT_KG = 30.0;
const MAX_WEIGHT_KG = 200.0;

// Shop
const SHOP_LAT = 34.752251;
const SHOP_LON = 135.546295;
const SHOP_ADDRESS = "〒533-0005 大阪府大阪市東淀川区瑞光4-7-6ビスタ瑞光101";

// ===== Menu Catalog =====
const MENU_CATEGORIES = [
  { id: "RAMEN", label: "ラーメン" },
  { id: "TOPPING", label: "トッピング" },
  { id: "RICE", label: "ご飯・サイド" },
  { id: "DRINK", label: "ドリンク" },
];

const MENU_ITEMS = [
  { id: "ramen", name: "ラーメン", priceYen: 600, category: "RAMEN", required: true },
  { id: "negi", name: "ねぎ", priceYen: 120, category: "TOPPING" },
  { id: "garlic", name: "ニンニク", priceYen: 100, category: "TOPPING" },
  { id: "corn", name: "コーン", priceYen: 100, category: "TOPPING" },
  { id: "boiled_egg", name: "煮卵", priceYen: 150, category: "TOPPING" },
  { id: "menma", name: "メンマ", priceYen: 150, category: "TOPPING" },
  { id: "kimchi", name: "キムチ", priceYen: 150, category: "TOPPING" },
  { id: "natto", name: "納豆", priceYen: 150, category: "TOPPING" },
  { id: "chashu_1", name: "チャーシュー1枚", priceYen: 120, category: "TOPPING" },
  { id: "chashu_2", name: "チャーシュー2枚", priceYen: 240, category: "TOPPING" },
  { id: "chashu_3", name: "チャーシュー3枚", priceYen: 360, category: "TOPPING" },
  { id: "extra_noodles", name: "替玉", priceYen: 170, category: "TOPPING" },
  { id: "rice_plain", name: "ご飯", priceYen: 200, category: "RICE" },
  { id: "rice_mini_chashu", name: "ミニチャーシュー丼", priceYen: 370, category: "RICE" },
  { id: "rice_mentaiko", name: "明太子ご飯", priceYen: 370, category: "RICE" },
  { id: "beer", name: "瓶ビール", priceYen: 650, category: "DRINK" },
  { id: "chuhai_plain", name: "プレーンチューハイ", priceYen: 500, category: "DRINK" },
  { id: "shochu_mugi", name: "麦焼酎", priceYen: 500, category: "DRINK" },
  { id: "shochu_imo", name: "芋焼酎", priceYen: 500, category: "DRINK" },
  { id: "kaku_highball", name: "角ハイボール", priceYen: 550, category: "DRINK" },
];

const PRICE_TABLE = Object.fromEntries(MENU_ITEMS.map((i) => [i.name, i.priceYen]));
const REQUIRED_NAMES = new Set(MENU_ITEMS.filter((i) => i.required).map((i) => i.name));
const CHASHU_NAMES = MENU_ITEMS.filter((i) => i.id.startsWith("chashu_")).map((i) => i.name);

// ===== Recommendation Engine =====
function recommend(metrics, weatherContext) {
  const tier = tierFromSteps(metrics.steps);
  const selectedNames = [...baseItemsByTier(tier)];
  const reasons = [`歩数 ${metrics.steps}歩に合わせた${tierLabel(tier)}構成`];

  const isColdOrWet =
    weatherContext.temperatureC <= 8 ||
    weatherContext.condition === "RAINY" ||
    weatherContext.condition === "SNOWY";
  const isHot = weatherContext.temperatureC >= 28;

  if (isColdOrWet) {
    addIfMissing(selectedNames, "ニンニク");
    if (tier !== "LIGHT") addIfMissing(selectedNames, "キムチ");
    reasons.push("低気温/雨雪なので温まり系を追加");
  } else if (isHot) {
    removeItem(selectedNames, "キムチ");
    removeItem(selectedNames, "ニンニク");
    replaceItem(selectedNames, "ミニチャーシュー丼", "明太子ご飯");
    addIfMissing(selectedNames, "ねぎ");
    addIfMissing(selectedNames, "コーン");
    if (tier !== "LIGHT") addIfMissing(selectedNames, "煮卵");
    reasons.push("高気温なのでさっぱり系を優先");
  } else {
    reasons.push("気温は中間帯なので標準バランス");
  }

  // Chashu recommendation based on exercise tier and weather
  if (tier === "REWARD") {
    addIfMissing(selectedNames, "チャーシュー3枚");
    reasons.push("高運動量なのでチャーシュー3枚でタンパク質補給");
  } else if (tier === "BALANCE" && isColdOrWet) {
    addIfMissing(selectedNames, "チャーシュー2枚");
    reasons.push("寒い日はチャーシュー2枚で栄養補給");
  } else if (tier === "BALANCE") {
    addIfMissing(selectedNames, "チャーシュー1枚");
  }

  const items = selectedNames.map((name) => ({ name, priceYen: PRICE_TABLE[name] ?? 0 }));
  const totalYen = items.reduce((sum, item) => sum + item.priceYen, 0);

  return { tier, items, reason: reasons.join(" / "), totalYen };
}

function tierFromSteps(steps) {
  if (steps < 4_000) return "LIGHT";
  if (steps < 10_000) return "BALANCE";
  return "REWARD";
}

function baseItemsByTier(tier) {
  if (tier === "LIGHT") return ["ラーメン", "ねぎ"];
  if (tier === "BALANCE") return ["ラーメン", "煮卵", "メンマ"];
  return ["ラーメン", "替玉", "ミニチャーシュー丼"];
}

function tierLabel(tier) {
  if (tier === "LIGHT") return "ライト";
  if (tier === "BALANCE") return "バランス";
  return "ご褒美";
}

function addIfMissing(arr, name) {
  if (!arr.includes(name)) arr.push(name);
}

function removeItem(arr, name) {
  const idx = arr.indexOf(name);
  if (idx >= 0) arr.splice(idx, 1);
}

function replaceItem(arr, oldName, newName) {
  const idx = arr.indexOf(oldName);
  if (idx >= 0) arr[idx] = newName;
}

// ===== State =====
const state = loadState();
state.activeTab = "home";
state.activitySubView = "dashboard";
state.todayMessage = "";
state.todayMessageIsError = false;
state.historyMessage = "";
state.historyMessageIsError = false;
state.settingsMessage = "";
state.settingsMessageIsError = false;
state.weatherMessage = "";
state.weatherMessageIsError = false;
state.weatherLoading = false;

const runtime = {
  motionListener: null,
  startInProgress: false,
  permission: "unknown",
  gravityMagnitude: 9.81,
  prevFiltered: 0,
  noiseEma: 1.0,
  lastPeakMs: 0,
  lastStepTimestampMs: null,
  recentStepTimestampsMs: [],
};

let persistTimer = 0;
let weatherRefreshTimer = null;

// ===== DOM refs =====
const els = {
  tabButtons: document.querySelectorAll(".tab-button"),
  tabPanels: document.querySelectorAll("[data-tab-panel]"),

  // Home
  homeSummary: document.getElementById("home-summary"),
  applyRecommendation: document.getElementById("apply-recommendation"),
  recCity: document.getElementById("rec-city"),
  recWeather: document.getElementById("rec-weather"),
  recTier: document.getElementById("rec-tier"),
  recItems: document.getElementById("rec-items"),
  recTotal: document.getElementById("rec-total"),
  recReason: document.getElementById("rec-reason"),
  recUpdated: document.getElementById("rec-updated"),
  homeSteps: document.getElementById("home-steps"),
  homeCalories: document.getElementById("home-calories"),
  homeBrisk: document.getElementById("home-brisk"),

  // Order
  applyRecSet: document.getElementById("apply-rec-set"),
  resetOrder: document.getElementById("reset-order"),
  menuCatalog: document.getElementById("menu-catalog"),
  currentOrder: document.getElementById("current-order"),
  orderTotal: document.getElementById("order-total"),

  // Activity
  activityDashboard: document.getElementById("activity-dashboard"),
  activityHistory: document.getElementById("activity-history"),
  showHistory: document.getElementById("show-history"),
  backToDashboard: document.getElementById("back-to-dashboard"),
  goToSettings: document.getElementById("go-to-settings"),
  trackingStatus: document.getElementById("tracking-status"),
  sensorStatus: document.getElementById("sensor-status"),
  todayMessage: document.getElementById("today-message"),
  startButton: document.getElementById("start-tracking"),
  stopButton: document.getElementById("stop-tracking"),
  manualStepButton: document.getElementById("manual-step"),
  resetTodayButton: document.getElementById("reset-today"),
  metricSteps: document.getElementById("metric-steps"),
  metricDistance: document.getElementById("metric-distance"),
  metricSpeed: document.getElementById("metric-speed"),
  metricCalories: document.getElementById("metric-calories"),
  metricBriskDuration: document.getElementById("metric-brisk-duration"),
  metricBriskDistance: document.getElementById("metric-brisk-distance"),
  metricRunningDuration: document.getElementById("metric-running-duration"),
  metricRunningDistance: document.getElementById("metric-running-distance"),
  lastUpdated: document.getElementById("last-updated"),

  // History
  filterButtons: document.querySelectorAll(".filter-button"),
  historyCount: document.getElementById("history-count"),
  summaryWeekSteps: document.getElementById("summary-week-steps"),
  summaryWeekDistance: document.getElementById("summary-week-distance"),
  summaryWeekCalories: document.getElementById("summary-week-calories"),
  summaryMonthSteps: document.getElementById("summary-month-steps"),
  summaryMonthDistance: document.getElementById("summary-month-distance"),
  summaryMonthCalories: document.getElementById("summary-month-calories"),
  historyChart: document.getElementById("history-chart"),
  historyMessage: document.getElementById("history-message"),
  historyTableBody: document.getElementById("history-table-body"),
  downloadCsvButton: document.getElementById("download-csv"),
  shareCsvButton: document.getElementById("share-csv"),

  // Settings - profile
  inputHeight: document.getElementById("input-height"),
  inputWeight: document.getElementById("input-weight"),
  sexChips: document.querySelectorAll(".chip[data-sex]"),
  inputStrideScale: document.getElementById("input-stride-scale"),
  strideScaleLabel: document.getElementById("stride-scale-label"),
  previewWalk: document.getElementById("preview-walk"),
  previewBrisk: document.getElementById("preview-brisk"),
  previewRunning: document.getElementById("preview-running"),
  previewWeight: document.getElementById("preview-weight"),
  inputCalibrationSteps: document.getElementById("input-calibration-steps"),
  inputCalibrationDistance: document.getElementById("input-calibration-distance"),
  recalcScaleButton: document.getElementById("recalc-scale"),
  saveSettingsButton: document.getElementById("save-settings"),
  settingsMessage: document.getElementById("settings-message"),

  // Settings - weather
  fetchWeatherButton: document.getElementById("fetch-weather"),
  weatherChips: document.querySelectorAll(".chip[data-weather]"),
  inputTemperature: document.getElementById("input-temperature"),
  saveWeatherButton: document.getElementById("save-weather"),
  weatherUpdated: document.getElementById("weather-updated"),
  weatherMessage: document.getElementById("weather-message"),

  // Settings - data
  clearLocalDataButton: document.getElementById("clear-local-data"),
};

// ===== Init =====
buildMenuCatalog();
bindEvents();
ensureCurrentDay();
renderAll();
registerServiceWorker();
scheduleWeatherAutoRefresh();

// ===== Event binding =====
function bindEvents() {
  els.tabButtons.forEach((button) => {
    button.addEventListener("click", () => {
      const nextTab = button.dataset.tab;
      if (nextTab) setActiveTab(nextTab);
    });
  });

  // Home
  els.applyRecommendation.addEventListener("click", () => {
    const rec = recommend(state.metrics, state.weatherContext);
    state.orderSelectedNames = rec.items
      .map((i) => i.name)
      .filter((n) => !REQUIRED_NAMES.has(n));
    schedulePersist();
    renderOrderChips();
    renderCurrentOrder();
    setActiveTab("order");
  });

  // Order
  els.applyRecSet.addEventListener("click", () => {
    const rec = recommend(state.metrics, state.weatherContext);
    state.orderSelectedNames = rec.items
      .map((i) => i.name)
      .filter((n) => !REQUIRED_NAMES.has(n));
    schedulePersist();
    renderOrderChips();
    renderCurrentOrder();
  });

  els.resetOrder.addEventListener("click", () => {
    state.orderSelectedNames = [];
    schedulePersist();
    renderOrderChips();
    renderCurrentOrder();
  });

  // Activity sub-views
  els.showHistory.addEventListener("click", () => {
    state.activitySubView = "history";
    els.activityDashboard.classList.add("is-hidden");
    els.activityHistory.classList.remove("is-hidden");
    renderHistory();
  });

  els.backToDashboard.addEventListener("click", () => {
    state.activitySubView = "dashboard";
    els.activityHistory.classList.add("is-hidden");
    els.activityDashboard.classList.remove("is-hidden");
  });

  els.goToSettings.addEventListener("click", () => {
    setActiveTab("settings");
  });

  // Tracking
  els.startButton.addEventListener("click", async () => {
    await startTracking();
  });

  els.stopButton.addEventListener("click", () => {
    stopTracking();
  });

  els.manualStepButton.addEventListener("click", () => {
    ensureCurrentDay();
    recordStep(Date.now());
    setTodayMessage("手動で1歩追加しました。");
  });

  els.resetTodayButton.addEventListener("click", () => {
    if (!window.confirm("今日の計測データをリセットしますか？")) return;
    state.metrics = newMetrics(currentDayEpoch());
    resetCadenceRuntime();
    schedulePersist(true);
    renderActivity();
    renderHome();
    setTodayMessage("今日のデータをリセットしました。");
  });

  // History filters
  els.filterButtons.forEach((button) => {
    button.addEventListener("click", () => {
      const raw = button.dataset.historyDays;
      state.historyRangeDays = raw === "all" ? null : Number.parseInt(raw ?? "", 10);
      schedulePersist();
      renderHistory();
    });
  });

  els.downloadCsvButton.addEventListener("click", () => {
    exportCsv(false).catch((error) => {
      setHistoryMessage(`CSV出力に失敗しました: ${error.message ?? "unknown"}`, true);
    });
  });

  els.shareCsvButton.addEventListener("click", () => {
    exportCsv(true).catch((error) => {
      setHistoryMessage(`CSV共有に失敗しました: ${error.message ?? "unknown"}`, true);
    });
  });

  // Settings - profile
  [els.inputHeight, els.inputWeight, els.inputStrideScale].forEach((input) => {
    input.addEventListener("input", () => renderSettingsPreview());
  });

  els.sexChips.forEach((chip) => {
    chip.addEventListener("click", () => {
      els.sexChips.forEach((c) => c.classList.remove("is-active"));
      chip.classList.add("is-active");
      renderSettingsPreview();
    });
  });

  els.recalcScaleButton.addEventListener("click", () => {
    recalculateStrideScaleFromCalibration();
  });

  els.saveSettingsButton.addEventListener("click", () => {
    saveSettings();
  });

  // Settings - weather chips
  els.weatherChips.forEach((chip) => {
    chip.addEventListener("click", () => {
      els.weatherChips.forEach((c) => c.classList.remove("is-active"));
      chip.classList.add("is-active");
    });
  });

  els.fetchWeatherButton.addEventListener("click", async () => {
    await fetchWeatherNow();
  });

  els.saveWeatherButton.addEventListener("click", () => {
    saveWeatherManually();
  });

  els.clearLocalDataButton.addEventListener("click", () => {
    clearLocalData();
  });

  window.addEventListener("beforeunload", () => {
    if (!persistTimer) return;
    clearTimeout(persistTimer);
    persistTimer = 0;
    persistState();
  });
}

// ===== Tab navigation =====
function setActiveTab(tab) {
  state.activeTab = tab;

  els.tabButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.tab === tab);
  });

  els.tabPanels.forEach((panel) => {
    panel.classList.toggle("is-hidden", panel.dataset.tabPanel !== tab);
  });

  if (tab === "activity") {
    if (state.activitySubView === "history") {
      els.activityDashboard.classList.add("is-hidden");
      els.activityHistory.classList.remove("is-hidden");
    } else {
      els.activityDashboard.classList.remove("is-hidden");
      els.activityHistory.classList.add("is-hidden");
    }
  }

  if (tab === "history" || (tab === "activity" && state.activitySubView === "history")) {
    renderHistory();
  }

  if (tab === "settings") {
    renderSettingsFromState();
  }
}

// ===== Render: Home =====
function renderHome() {
  const m = state.metrics;
  const wc = state.weatherContext;
  const rec = recommend(m, wc);

  els.homeSummary.textContent =
    `運動量 ${m.steps}歩 と ${weatherLabel(wc.condition)} ${wc.temperatureC}°C から提案`;

  els.recCity.textContent = SHOP_ADDRESS;
  els.recWeather.textContent = `${weatherLabel(wc.condition)} / ${wc.temperatureC}°C`;
  els.recTier.textContent = tierLabel(rec.tier);

  els.recItems.innerHTML = "";
  rec.items.forEach((item) => {
    const row = document.createElement("div");
    row.className = "metric-row";
    row.innerHTML = `<span>${item.name}</span><span>${formatYen(item.priceYen)}</span>`;
    els.recItems.appendChild(row);
  });

  els.recTotal.textContent = formatYen(rec.totalYen);
  els.recReason.textContent = rec.reason;
  els.recUpdated.textContent = state.weatherUpdatedAtEpochMs > 0
    ? `天気更新: ${formatDateTime(state.weatherUpdatedAtEpochMs)}`
    : "";

  els.homeSteps.textContent = `${m.steps} 歩`;
  els.homeCalories.textContent = formatCalories(m.totalCaloriesKcal);
  els.homeBrisk.textContent = formatDuration(m.briskDurationMs);
}

// ===== Render: Order =====
function buildMenuCatalog() {
  MENU_CATEGORIES.forEach((category) => {
    const items = MENU_ITEMS.filter((item) => item.category === category.id);
    if (items.length === 0) return;

    const section = document.createElement("article");
    section.className = "card";

    const title = document.createElement("h3");
    title.textContent = category.label;
    section.appendChild(title);

    const chipGroup = document.createElement("div");
    chipGroup.className = "chip-group";

    items.forEach((item) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "menu-chip";
      chip.dataset.itemName = item.name;
      chip.textContent = `${item.name}　${formatYen(item.priceYen)}`;

      if (item.required) {
        chip.classList.add("is-active");
        chip.disabled = true;
      }

      if (!item.required) {
        chip.addEventListener("click", () => {
          toggleOrderItem(item.name);
        });
      }

      chipGroup.appendChild(chip);
    });

    section.appendChild(chipGroup);
    els.menuCatalog.appendChild(section);
  });
}

function toggleOrderItem(name) {
  if (CHASHU_NAMES.includes(name)) {
    const wasSelected = state.orderSelectedNames.includes(name);
    state.orderSelectedNames = state.orderSelectedNames.filter((n) => !CHASHU_NAMES.includes(n));
    if (!wasSelected) state.orderSelectedNames.push(name);
  } else {
    const idx = state.orderSelectedNames.indexOf(name);
    if (idx >= 0) {
      state.orderSelectedNames.splice(idx, 1);
    } else {
      state.orderSelectedNames.push(name);
    }
  }
  schedulePersist();
  renderOrderChips();
  renderCurrentOrder();
}

function renderOrder() {
  renderOrderChips();
  renderCurrentOrder();
}

function renderOrderChips() {
  const chips = els.menuCatalog.querySelectorAll(".menu-chip[data-item-name]");
  chips.forEach((chip) => {
    const name = chip.dataset.itemName;
    if (REQUIRED_NAMES.has(name)) return;
    chip.classList.toggle("is-active", state.orderSelectedNames.includes(name));
  });
}

function renderCurrentOrder() {
  const allSelected = [
    ...Array.from(REQUIRED_NAMES),
    ...state.orderSelectedNames.filter((n) => !REQUIRED_NAMES.has(n)),
  ];
  const sorted = [...allSelected].sort((a, b) => (PRICE_TABLE[a] ?? 0) - (PRICE_TABLE[b] ?? 0));

  els.currentOrder.innerHTML = "";
  sorted.forEach((name) => {
    const price = PRICE_TABLE[name] ?? 0;
    const row = document.createElement("div");
    row.className = "metric-row";
    row.innerHTML = `<span>${name}</span><span>${formatYen(price)}</span>`;
    els.currentOrder.appendChild(row);
  });

  const total = sorted.reduce((sum, name) => sum + (PRICE_TABLE[name] ?? 0), 0);
  els.orderTotal.textContent = formatYen(total);
}

// ===== Render: Activity =====
function renderActivity() {
  const metrics = state.metrics;

  const isTracking = state.isTracking;
  els.trackingStatus.textContent = isTracking ? "計測中" : "停止中";
  els.trackingStatus.classList.toggle("stopped", !isTracking);

  let sensorText = "センサー利用可";
  if (!supportsDeviceMotion()) {
    sensorText = "このブラウザはモーションセンサー非対応です";
  } else if (runtime.permission !== "granted" && needsMotionPermissionPrompt()) {
    sensorText = "iOSでは開始時にモーション許可が必要です";
  } else if (!state.sensorSupported) {
    sensorText = "モーション権限またはセンサー入力が利用できません";
  }
  els.sensorStatus.textContent = sensorText;

  els.startButton.disabled = isTracking || runtime.startInProgress;
  els.stopButton.disabled = !isTracking;

  els.metricSteps.textContent = `${metrics.steps} 歩`;
  els.metricDistance.textContent = formatDistance(metrics.totalDistanceMeters);
  els.metricSpeed.textContent = formatSpeed(averageSpeedMps(metrics));
  els.metricCalories.textContent = formatCalories(metrics.totalCaloriesKcal);
  els.metricBriskDuration.textContent = formatDuration(metrics.briskDurationMs);
  els.metricBriskDistance.textContent = formatDistance(metrics.briskDistanceMeters);
  els.metricRunningDuration.textContent = formatDuration(metrics.runningDurationMs);
  els.metricRunningDistance.textContent = formatDistance(metrics.runningDistanceMeters);

  els.lastUpdated.textContent = metrics.lastUpdatedEpochMs > 0
    ? `最終更新: ${formatDateTime(metrics.lastUpdatedEpochMs)}`
    : "";

  els.todayMessage.textContent = state.todayMessage;
  els.todayMessage.classList.toggle("error", state.todayMessageIsError === true);
}

// ===== Render: History =====
function renderHistory() {
  const filtered = filteredHistory(state.history, state.historyRangeDays);
  const weekly = summarizeLastDays(state.history, 7);
  const monthly = summarizeLastDays(state.history, 30);

  els.filterButtons.forEach((button) => {
    const raw = button.dataset.historyDays;
    const buttonDays = raw === "all" ? null : Number.parseInt(raw ?? "", 10);
    button.classList.toggle("is-active", buttonDays === state.historyRangeDays);
  });

  els.historyCount.textContent = `表示件数: ${filtered.length} 日分`;

  els.summaryWeekSteps.textContent = `${weekly.steps} 歩`;
  els.summaryWeekDistance.textContent = formatDistance(weekly.distanceMeters);
  els.summaryWeekCalories.textContent = formatCalories(weekly.caloriesKcal);

  els.summaryMonthSteps.textContent = `${monthly.steps} 歩`;
  els.summaryMonthDistance.textContent = formatDistance(monthly.distanceMeters);
  els.summaryMonthCalories.textContent = formatCalories(monthly.caloriesKcal);

  renderHistoryChart(filtered);
  renderHistoryTable(filtered);

  const canExport = filtered.length > 0;
  els.downloadCsvButton.disabled = !canExport;
  els.shareCsvButton.disabled = !canExport;

  els.historyMessage.textContent = state.historyMessage;
  els.historyMessage.classList.toggle("error", state.historyMessageIsError === true);
}

function renderHistoryChart(days) {
  els.historyChart.innerHTML = "";

  if (days.length === 0) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "表示できる履歴データがありません。";
    els.historyChart.appendChild(empty);
    return;
  }

  const sortedAsc = [...days].sort((a, b) => a.dayEpoch - b.dayEpoch);
  const maxSteps = Math.max(...sortedAsc.map((d) => d.steps), 1);

  sortedAsc.forEach((day) => {
    const bar = document.createElement("div");
    bar.className = "history-bar";
    const ratio = day.steps / maxSteps;
    const heightPx = 10 + Math.round(ratio * 100);
    bar.style.height = `${heightPx}px`;
    const label = document.createElement("span");
    label.textContent = formatDay(day.dayEpoch);
    bar.appendChild(label);
    els.historyChart.appendChild(bar);
  });
}

function renderHistoryTable(days) {
  els.historyTableBody.innerHTML = "";

  if (days.length === 0) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 4;
    cell.className = "muted";
    cell.textContent = "履歴データがありません。";
    row.appendChild(cell);
    els.historyTableBody.appendChild(row);
    return;
  }

  days.forEach((day) => {
    const row = document.createElement("tr");
    [
      formatDay(day.dayEpoch),
      `${day.steps} 歩`,
      formatDistance(day.totalDistanceMeters),
      formatCalories(day.totalCaloriesKcal),
    ].forEach((text) => {
      const cell = document.createElement("td");
      cell.textContent = text;
      row.appendChild(cell);
    });
    els.historyTableBody.appendChild(row);
  });
}

// ===== Render: Settings =====
function renderSettingsFromState() {
  const profile = state.profile;

  els.inputHeight.value = String(profile.heightCm);
  els.inputWeight.value = profile.weightKg == null ? "" : formatWeightInput(profile.weightKg);
  els.inputStrideScale.value = String(profile.strideScale);

  els.sexChips.forEach((chip) => {
    chip.classList.toggle("is-active", chip.dataset.sex === profile.sex);
  });

  els.inputTemperature.value = String(state.weatherContext.temperatureC);
  els.weatherChips.forEach((chip) => {
    chip.classList.toggle("is-active", chip.dataset.weather === state.weatherContext.condition);
  });

  els.weatherUpdated.textContent = state.weatherUpdatedAtEpochMs > 0
    ? `最終更新: ${formatDateTime(state.weatherUpdatedAtEpochMs)}`
    : "";

  renderSettingsPreview();
  setSettingsMessage(state.settingsMessage, state.settingsMessageIsError);
}

function renderSettingsPreview() {
  const result = parseProfileFromInputs(false);
  const profile = result.profile;

  els.strideScaleLabel.textContent = `${profile.strideScale.toFixed(2)}x`;

  const stride = estimateStride(profile);
  els.previewWalk.textContent = formatMeters(stride.walkMeters);
  els.previewBrisk.textContent = formatMeters(stride.briskMeters);
  els.previewRunning.textContent = formatMeters(stride.runMeters);

  const weightKg = estimatedWeightKg(profile);
  els.previewWeight.textContent = `${formatWeightInput(weightKg)} kg`;
}

// ===== Settings actions =====
function recalculateStrideScaleFromCalibration() {
  const steps = Number.parseInt(els.inputCalibrationSteps.value, 10);
  const distanceMeters = Number.parseFloat(els.inputCalibrationDistance.value);

  if (!Number.isFinite(steps) || steps <= 0 || !Number.isFinite(distanceMeters) || distanceMeters <= 0) {
    setSettingsMessage("実測歩数と実測距離を正しく入力してください。", true);
    return;
  }

  const profile = parseProfileFromInputs(false).profile;
  const baseWalk = estimateStride({ ...profile, strideScale: 1.0 }).walkMeters;
  const recalculated = clamp((distanceMeters / steps) / baseWalk, MIN_STRIDE_SCALE, MAX_STRIDE_SCALE);

  els.inputStrideScale.value = recalculated.toFixed(2);
  renderSettingsPreview();
  setSettingsMessage("補正係数を再計算しました。必要なら「設定を保存」を押してください。", false);
}

function saveSettings() {
  const parsed = parseProfileFromInputs(true);
  if (!parsed.ok) {
    setSettingsMessage(parsed.errorMessage, true);
    return;
  }

  state.profile = normalizeProfile(parsed.profile);
  schedulePersist(true);
  setSettingsMessage("保存しました。", false);
  renderSettingsPreview();
}

function saveWeatherManually() {
  const activeChip = document.querySelector(".chip.is-active[data-weather]");
  const condition = activeChip?.dataset.weather ?? "SUNNY";
  const temperature = Number.parseInt(els.inputTemperature.value, 10);

  if (!Number.isFinite(temperature) || temperature < -20 || temperature > 45) {
    setWeatherMessage("気温は -20〜45 °C の範囲で入力してください。", true);
    return;
  }

  state.weatherContext = { condition, temperatureC: temperature };
  schedulePersist(true);
  setWeatherMessage("天候設定を保存しました。", false);
  renderHome();
}

function clearLocalData() {
  if (!window.confirm("保存済みの設定と履歴データを削除しますか？")) return;

  stopTracking();
  state.profile = normalizeProfile();
  state.metrics = newMetrics(currentDayEpoch());
  state.history = [];
  state.historyRangeDays = null;
  state.orderSelectedNames = [];
  state.weatherContext = { condition: "SUNNY", temperatureC: 20 };
  state.weatherUpdatedAtEpochMs = 0;
  state.sensorSupported = true;
  resetCadenceRuntime();

  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch (_error) {
    // ignore
  }

  setHistoryMessage("", false);
  setTodayMessage("");
  setSettingsMessage("保存データを削除しました。", false);
  setWeatherMessage("", false);
  schedulePersist(true);
  renderAll();
}

// ===== Weather fetch =====
async function fetchWeatherNow() {
  if (state.weatherLoading) return;
  state.weatherLoading = true;
  els.fetchWeatherButton.disabled = true;
  els.fetchWeatherButton.textContent = "取得中...";
  setWeatherMessage("", false);

  try {
    const wc = await fetchWeatherForShop();
    state.weatherContext = wc;
    state.weatherUpdatedAtEpochMs = Date.now();
    schedulePersist(true);

    // Update settings UI
    els.inputTemperature.value = String(wc.temperatureC);
    els.weatherChips.forEach((chip) => {
      chip.classList.toggle("is-active", chip.dataset.weather === wc.condition);
    });
    els.weatherUpdated.textContent = `最終更新: ${formatDateTime(state.weatherUpdatedAtEpochMs)}`;
    setWeatherMessage("店舗天気を更新しました。", false);

    renderHome();
  } catch (error) {
    setWeatherMessage(`自動取得に失敗しました: ${error.message ?? "unknown"}`, true);
  } finally {
    state.weatherLoading = false;
    els.fetchWeatherButton.disabled = false;
    els.fetchWeatherButton.textContent = "店舗天気を今すぐ更新";
  }
}

async function fetchWeatherForShop() {
  const url =
    `https://api.open-meteo.com/v1/forecast?latitude=${SHOP_LAT}&longitude=${SHOP_LON}&current=temperature_2m,weather_code&timezone=auto`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`天気APIエラー(${response.status})`);
  const data = await response.json();
  const current = data.current;
  if (!current) throw new Error("天気データを取得できませんでした。");
  return {
    condition: weatherConditionFromCode(current.weather_code),
    temperatureC: Math.round(current.temperature_2m),
  };
}

function weatherConditionFromCode(code) {
  if ([0, 1].includes(code)) return "SUNNY";
  if ([2, 3, 45, 48].includes(code)) return "CLOUDY";
  if ([51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99].includes(code)) return "RAINY";
  if ([71, 73, 75, 77, 85, 86].includes(code)) return "SNOWY";
  return "CLOUDY";
}

function scheduleWeatherAutoRefresh() {
  // Initial fetch after short delay (non-blocking)
  const shouldFetchNow =
    state.weatherUpdatedAtEpochMs === 0 ||
    Date.now() - state.weatherUpdatedAtEpochMs >= WEATHER_REFRESH_MS;

  if (shouldFetchNow) {
    setTimeout(() => { fetchWeatherNow(); }, 800);
  }

  // Auto-refresh every 15 minutes
  weatherRefreshTimer = setInterval(() => {
    const stale = Date.now() - state.weatherUpdatedAtEpochMs >= WEATHER_REFRESH_MS;
    if (stale) fetchWeatherNow();
  }, WEATHER_REFRESH_MS);
}

// ===== Tracking =====
async function startTracking() {
  if (runtime.startInProgress) return;
  ensureCurrentDay();
  runtime.startInProgress = true;
  renderActivity();

  try {
    if (!supportsDeviceMotion()) {
      state.sensorSupported = false;
      state.isTracking = false;
      setTodayMessage("このブラウザではモーションセンサーが利用できません。手動追加を使ってください。", true);
      return;
    }

    const permissionGranted = await ensureMotionPermission();
    if (!permissionGranted) {
      state.sensorSupported = false;
      state.isTracking = false;
      setTodayMessage("モーション権限が未許可です。ブラウザ設定から許可してください。", true);
      return;
    }

    if (runtime.motionListener) {
      state.isTracking = true;
      return;
    }

    runtime.motionListener = (event) => handleDeviceMotion(event);
    window.addEventListener("devicemotion", runtime.motionListener, { passive: true });

    state.sensorSupported = true;
    state.isTracking = true;
    setTodayMessage("計測を開始しました。");
    schedulePersist(true);
  } finally {
    runtime.startInProgress = false;
    renderActivity();
  }
}

function stopTracking() {
  if (runtime.motionListener) {
    window.removeEventListener("devicemotion", runtime.motionListener);
    runtime.motionListener = null;
  }

  state.isTracking = false;
  schedulePersist(true);
  setTodayMessage("計測を停止しました。");
  renderActivity();
}

function handleDeviceMotion(event) {
  ensureCurrentDay();

  const acceleration = event.accelerationIncludingGravity ?? event.acceleration;
  if (!acceleration) return;

  const x = Number(acceleration.x) || 0;
  const y = Number(acceleration.y) || 0;
  const z = Number(acceleration.z) || 0;
  const magnitude = Math.sqrt(x * x + y * y + z * z);

  runtime.gravityMagnitude = runtime.gravityMagnitude * 0.9 + magnitude * 0.1;
  const filtered = magnitude - runtime.gravityMagnitude;
  runtime.noiseEma = runtime.noiseEma * 0.95 + Math.abs(filtered) * 0.05;
  const threshold = Math.max(1.05, runtime.noiseEma * 1.35);

  const nowMs = Date.now();
  const isPeak = filtered > threshold && runtime.prevFiltered <= threshold;
  const elapsedSincePeak = nowMs - runtime.lastPeakMs;

  if (isPeak && elapsedSincePeak >= 250) {
    runtime.lastPeakMs = nowMs;
    recordStep(nowMs);
  }

  runtime.prevFiltered = filtered;
}

function recordStep(timestampMs) {
  ensureCurrentDay();

  runtime.recentStepTimestampsMs.push(timestampMs);
  while (
    runtime.recentStepTimestampsMs.length > 0 &&
    timestampMs - runtime.recentStepTimestampsMs[0] > CADENCE_WINDOW_MS
  ) {
    runtime.recentStepTimestampsMs.shift();
  }

  const cadenceSpm = calculateCadenceSpm(timestampMs);
  const zone = zoneFromCadence(cadenceSpm);
  const strideMeters = strideLengthMeters(zone, state.profile);
  const stepCaloriesKcal = estimateStepCaloriesKcal(zone, state.profile, strideMeters);
  const movementDeltaMs = calculateMovementDelta(timestampMs);

  const previous = state.metrics;
  state.metrics = {
    ...previous,
    steps: previous.steps + 1,
    totalDistanceMeters: previous.totalDistanceMeters + strideMeters,
    totalCaloriesKcal: previous.totalCaloriesKcal + stepCaloriesKcal,
    movingDurationMs: previous.movingDurationMs + movementDeltaMs,
    briskDistanceMeters: previous.briskDistanceMeters + (zone === "BRISK" ? strideMeters : 0),
    briskDurationMs: previous.briskDurationMs + (zone === "BRISK" ? movementDeltaMs : 0),
    runningDistanceMeters: previous.runningDistanceMeters + (zone === "RUNNING" ? strideMeters : 0),
    runningDurationMs: previous.runningDurationMs + (zone === "RUNNING" ? movementDeltaMs : 0),
    lastUpdatedEpochMs: timestampMs,
  };

  schedulePersist();
  renderActivity();
  renderHome();
}

function calculateMovementDelta(nowMs) {
  if (runtime.lastStepTimestampMs == null) {
    runtime.lastStepTimestampMs = nowMs;
    return 0;
  }

  const delta = nowMs - runtime.lastStepTimestampMs;
  runtime.lastStepTimestampMs = nowMs;

  if (delta < MIN_STEP_GAP_MS || delta > MAX_STEP_GAP_MS) return 0;
  return delta;
}

function calculateCadenceSpm(nowMs) {
  const timestamps = runtime.recentStepTimestampsMs;
  if (timestamps.length < 3) return 0;
  const oldest = timestamps[0];
  const windowSeconds = Math.max((nowMs - oldest) / 1_000, 1);
  const stepIntervals = Math.max(timestamps.length - 1, 1);
  return (stepIntervals / windowSeconds) * 60;
}

function zoneFromCadence(cadenceSpm) {
  if (cadenceSpm >= RUNNING_CADENCE_SPM) return "RUNNING";
  if (cadenceSpm >= BRISK_CADENCE_SPM) return "BRISK";
  return "WALKING";
}

function strideLengthMeters(zone, profile) {
  const stride = estimateStride(profile);
  if (zone === "BRISK") return stride.briskMeters;
  if (zone === "RUNNING") return stride.runMeters;
  return stride.walkMeters;
}

function estimateStride(profile) {
  const normalized = normalizeProfile(profile);
  const heightMeters = normalized.heightCm / 100;

  let baseRatio = 0.414;
  if (normalized.sex === "MALE") baseRatio = 0.415;
  if (normalized.sex === "FEMALE") baseRatio = 0.413;

  const walk = clamp(heightMeters * baseRatio * normalized.strideScale, 0.45, 1.1);
  return {
    walkMeters: walk,
    briskMeters: clamp(walk * 1.12, 0.5, 1.25),
    runMeters: clamp(walk * 1.5, 0.65, 1.8),
  };
}

function estimateStepCaloriesKcal(zone, profile, strideMeters) {
  const distanceKm = strideMeters / 1_000;
  const weightKg = estimatedWeightKg(profile);

  let kcalPerKgPerKm = 0.9;
  if (zone === "BRISK") kcalPerKgPerKm = 1.0;
  if (zone === "RUNNING") kcalPerKgPerKm = 1.08;

  return Math.max(weightKg * kcalPerKgPerKm * distanceKm, 0);
}

function estimatedWeightKg(profile) {
  const normalized = normalizeProfile(profile);
  if (normalized.weightKg != null) return normalized.weightKg;

  const heightMeters = normalized.heightCm / 100;
  let bmi = 22.0;
  if (normalized.sex === "MALE") bmi = 22.5;
  if (normalized.sex === "FEMALE") bmi = 21.5;

  return clamp(bmi * heightMeters * heightMeters, 40.0, 120.0);
}

// ===== Day management =====
function ensureCurrentDay() {
  const today = currentDayEpoch();
  if (state.metrics.dayEpoch === today) return false;

  archiveMetricsIfNeeded(state.metrics);
  state.metrics = newMetrics(today);
  resetCadenceRuntime();
  schedulePersist(true);
  return true;
}

function archiveMetricsIfNeeded(metrics) {
  if (!hasActivity(metrics)) return;

  const day = {
    dayEpoch: metrics.dayEpoch,
    steps: metrics.steps,
    totalDistanceMeters: metrics.totalDistanceMeters,
    totalCaloriesKcal: metrics.totalCaloriesKcal,
    movingDurationMs: metrics.movingDurationMs,
    briskDistanceMeters: metrics.briskDistanceMeters,
    briskDurationMs: metrics.briskDurationMs,
    runningDistanceMeters: metrics.runningDistanceMeters,
    runningDurationMs: metrics.runningDurationMs,
  };

  state.history = upsertHistory(state.history, day);
}

function resetCadenceRuntime() {
  runtime.lastPeakMs = 0;
  runtime.lastStepTimestampMs = null;
  runtime.recentStepTimestampsMs = [];
}

function hasActivity(metrics) {
  return (
    metrics.steps > 0 ||
    metrics.totalDistanceMeters > 0 ||
    metrics.totalCaloriesKcal > 0 ||
    metrics.movingDurationMs > 0 ||
    metrics.briskDurationMs > 0 ||
    metrics.runningDurationMs > 0
  );
}

// ===== Render all =====
function renderAll() {
  setActiveTab(state.activeTab);
  renderHome();
  renderOrder();
  renderActivity();
  renderSettingsFromState();
}

// ===== CSV export =====
async function exportCsv(share) {
  const rows = filteredHistory(state.history, state.historyRangeDays);
  if (rows.length === 0) {
    setHistoryMessage("履歴データがないためCSVを出力できません。", true);
    return;
  }

  const csv = toCsv(rows);
  const filename = `makimura_history_${fileTimestamp()}.csv`;

  if (!share) {
    triggerCsvDownload(csv, filename);
    setHistoryMessage("CSVを保存しました。", false);
    return;
  }

  const file = new File([csv], filename, { type: "text/csv" });
  if (navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ title: "運動履歴 CSV", files: [file] });
      setHistoryMessage("CSV共有シートを開きました。", false);
    } catch (error) {
      if (error && error.name === "AbortError") return;
      throw error;
    }
    return;
  }

  triggerCsvDownload(csv, filename);
  setHistoryMessage("共有API非対応のためCSVをダウンロードしました。", false);
}

function triggerCsvDownload(csv, filename) {
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);

  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);

  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function toCsv(rows) {
  const sorted = [...rows].sort((a, b) => a.dayEpoch - b.dayEpoch);
  const lines = [
    "date,steps,total_distance_m,total_calories_kcal,moving_duration_s,brisk_distance_m,brisk_duration_s,running_distance_m,running_duration_s",
  ];

  sorted.forEach((day) => {
    lines.push(
      [
        formatCsvDate(day.dayEpoch),
        String(day.steps),
        formatCsvDouble(day.totalDistanceMeters),
        formatCsvDouble(day.totalCaloriesKcal),
        String(Math.floor(day.movingDurationMs / 1_000)),
        formatCsvDouble(day.briskDistanceMeters),
        String(Math.floor(day.briskDurationMs / 1_000)),
        formatCsvDouble(day.runningDistanceMeters),
        String(Math.floor(day.runningDurationMs / 1_000)),
      ].join(",")
    );
  });

  return `${lines.join("\n")}\n`;
}

// ===== History filtering =====
function filteredHistory(history, days) {
  const sorted = [...history].sort((a, b) => b.dayEpoch - a.dayEpoch);
  if (days == null) return sorted;

  const today = currentDayEpoch();
  const minDay = today - (Math.max(days, 1) - 1);
  return sorted.filter((d) => d.dayEpoch >= minDay && d.dayEpoch <= today);
}

function summarizeLastDays(history, days) {
  const filtered = filteredHistory(history, days);
  return {
    steps: filtered.reduce((sum, d) => sum + d.steps, 0),
    distanceMeters: filtered.reduce((sum, d) => sum + d.totalDistanceMeters, 0),
    caloriesKcal: filtered.reduce((sum, d) => sum + d.totalCaloriesKcal, 0),
  };
}

// ===== State persistence =====
function loadState() {
  const fallback = {
    profile: normalizeProfile(),
    metrics: newMetrics(currentDayEpoch()),
    history: [],
    historyRangeDays: null,
    weatherContext: { condition: "SUNNY", temperatureC: 20 },
    weatherUpdatedAtEpochMs: 0,
    orderSelectedNames: [],
    isTracking: false,
    sensorSupported: true,
  };

  let parsed = null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    parsed = raw ? JSON.parse(raw) : null;
  } catch (_error) {
    parsed = null;
  }

  if (!parsed || typeof parsed !== "object") return fallback;

  const wc = parsed.weatherContext ?? {};
  const validConditions = ["SUNNY", "CLOUDY", "RAINY", "SNOWY"];

  const loaded = {
    profile: normalizeProfile(parsed.profile),
    metrics: normalizeMetrics(parsed.metrics),
    history: normalizeHistory(parsed.history),
    historyRangeDays: normalizeHistoryRangeDays(parsed.historyRangeDays),
    weatherContext: {
      condition: validConditions.includes(wc.condition) ? wc.condition : "SUNNY",
      temperatureC: typeof wc.temperatureC === "number" ? Math.round(clamp(wc.temperatureC, -20, 45)) : 20,
    },
    weatherUpdatedAtEpochMs: toInteger(parsed.weatherUpdatedAtEpochMs, 0),
    orderSelectedNames: Array.isArray(parsed.orderSelectedNames)
      ? parsed.orderSelectedNames.filter((n) => typeof n === "string" && PRICE_TABLE[n] != null)
      : [],
    isTracking: false,
    sensorSupported: true,
  };

  if (loaded.metrics.dayEpoch !== currentDayEpoch()) {
    if (hasActivity(loaded.metrics)) {
      loaded.history = upsertHistory(loaded.history, {
        dayEpoch: loaded.metrics.dayEpoch,
        steps: loaded.metrics.steps,
        totalDistanceMeters: loaded.metrics.totalDistanceMeters,
        totalCaloriesKcal: loaded.metrics.totalCaloriesKcal,
        movingDurationMs: loaded.metrics.movingDurationMs,
        briskDistanceMeters: loaded.metrics.briskDistanceMeters,
        briskDurationMs: loaded.metrics.briskDurationMs,
        runningDistanceMeters: loaded.metrics.runningDistanceMeters,
        runningDurationMs: loaded.metrics.runningDurationMs,
      });
    }
    loaded.metrics = newMetrics(currentDayEpoch());
  }

  return loaded;
}

function schedulePersist(immediate = false) {
  if (immediate) {
    if (persistTimer) { clearTimeout(persistTimer); persistTimer = 0; }
    persistState();
    return;
  }
  if (persistTimer) return;
  persistTimer = window.setTimeout(() => { persistTimer = 0; persistState(); }, 800);
}

function persistState() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      profile: state.profile,
      metrics: state.metrics,
      history: state.history,
      historyRangeDays: state.historyRangeDays,
      weatherContext: state.weatherContext,
      weatherUpdatedAtEpochMs: state.weatherUpdatedAtEpochMs,
      orderSelectedNames: state.orderSelectedNames,
    }));
  } catch (_error) {
    setTodayMessage("ローカル保存に失敗しました。ブラウザ容量を確認してください。", true);
  }
}

// ===== Data normalization =====
function normalizeProfile(profile) {
  const source = profile ?? {};
  const heightCm = clamp(toNumber(source.heightCm, 170), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
  const strideScale = clamp(toNumber(source.strideScale, 1.0), MIN_STRIDE_SCALE, MAX_STRIDE_SCALE);

  let sex = "OTHER";
  if (source.sex === "MALE" || source.sex === "FEMALE" || source.sex === "OTHER") {
    sex = source.sex;
  }

  let weightKg = null;
  if (source.weightKg != null) {
    const n = Number(source.weightKg);
    if (Number.isFinite(n)) weightKg = clamp(n, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
  }

  return { heightCm, sex, strideScale, weightKg };
}

function normalizeMetrics(metrics) {
  const source = metrics ?? {};
  return {
    dayEpoch: toInteger(source.dayEpoch, currentDayEpoch()),
    steps: Math.max(0, toInteger(source.steps, 0)),
    totalDistanceMeters: Math.max(0, toNumber(source.totalDistanceMeters, 0)),
    totalCaloriesKcal: Math.max(0, toNumber(source.totalCaloriesKcal, 0)),
    movingDurationMs: Math.max(0, toInteger(source.movingDurationMs, 0)),
    briskDistanceMeters: Math.max(0, toNumber(source.briskDistanceMeters, 0)),
    briskDurationMs: Math.max(0, toInteger(source.briskDurationMs, 0)),
    runningDistanceMeters: Math.max(0, toNumber(source.runningDistanceMeters, 0)),
    runningDurationMs: Math.max(0, toInteger(source.runningDurationMs, 0)),
    lastUpdatedEpochMs: Math.max(0, toInteger(source.lastUpdatedEpochMs, 0)),
  };
}

function normalizeHistory(history) {
  if (!Array.isArray(history)) return [];

  const normalized = history
    .map((item) => ({
      dayEpoch: toInteger(item?.dayEpoch, NaN),
      steps: Math.max(0, toInteger(item?.steps, 0)),
      totalDistanceMeters: Math.max(0, toNumber(item?.totalDistanceMeters, 0)),
      totalCaloriesKcal: Math.max(0, toNumber(item?.totalCaloriesKcal, 0)),
      movingDurationMs: Math.max(0, toInteger(item?.movingDurationMs, 0)),
      briskDistanceMeters: Math.max(0, toNumber(item?.briskDistanceMeters, 0)),
      briskDurationMs: Math.max(0, toInteger(item?.briskDurationMs, 0)),
      runningDistanceMeters: Math.max(0, toNumber(item?.runningDistanceMeters, 0)),
      runningDurationMs: Math.max(0, toInteger(item?.runningDurationMs, 0)),
    }))
    .filter((item) => Number.isFinite(item.dayEpoch) && item.dayEpoch > 0)
    .sort((a, b) => b.dayEpoch - a.dayEpoch);

  const unique = [];
  const seen = new Set();
  for (const day of normalized) {
    if (seen.has(day.dayEpoch)) continue;
    seen.add(day.dayEpoch);
    unique.push(day);
  }

  return unique.slice(0, HISTORY_LIMIT);
}

function normalizeHistoryRangeDays(days) {
  if (days === 7 || days === 30) return days;
  return null;
}

function upsertHistory(history, day) {
  const filtered = history.filter((e) => e.dayEpoch !== day.dayEpoch);
  return [day, ...filtered].sort((a, b) => b.dayEpoch - a.dayEpoch).slice(0, HISTORY_LIMIT);
}

function newMetrics(dayEpoch) {
  return {
    dayEpoch,
    steps: 0,
    totalDistanceMeters: 0,
    totalCaloriesKcal: 0,
    movingDurationMs: 0,
    briskDistanceMeters: 0,
    briskDurationMs: 0,
    runningDistanceMeters: 0,
    runningDurationMs: 0,
    lastUpdatedEpochMs: 0,
  };
}

function parseProfileFromInputs(strictValidation) {
  const heightRaw = els.inputHeight.value.trim();
  const weightRaw = els.inputWeight.value.trim();
  const activeChip = document.querySelector(".chip.is-active[data-sex]");
  const sexRaw = activeChip?.dataset.sex ?? "OTHER";
  const strideScaleRaw = Number.parseFloat(els.inputStrideScale.value);

  const parsedHeight = Number.parseInt(heightRaw, 10);
  if (strictValidation && (!Number.isFinite(parsedHeight) || parsedHeight < MIN_HEIGHT_CM || parsedHeight > MAX_HEIGHT_CM)) {
    return { ok: false, errorMessage: "身長は 120〜220 cm の範囲で入力してください。" };
  }

  let parsedWeight = null;
  if (weightRaw !== "") {
    const numeric = Number.parseFloat(weightRaw);
    const validRange = Number.isFinite(numeric) && numeric >= MIN_WEIGHT_KG && numeric <= MAX_WEIGHT_KG;
    if (strictValidation && !validRange) {
      return { ok: false, errorMessage: "体重は 30.0〜200.0 kg の範囲で入力してください。" };
    }
    if (validRange) parsedWeight = numeric;
  }

  const validSex = sexRaw === "MALE" || sexRaw === "FEMALE" || sexRaw === "OTHER";
  const sex = validSex ? sexRaw : "OTHER";

  const fallback = state.profile;
  const profile = {
    heightCm: Number.isFinite(parsedHeight) ? clamp(parsedHeight, MIN_HEIGHT_CM, MAX_HEIGHT_CM) : fallback.heightCm,
    sex,
    strideScale: Number.isFinite(strideScaleRaw) ? clamp(strideScaleRaw, MIN_STRIDE_SCALE, MAX_STRIDE_SCALE) : fallback.strideScale,
    weightKg: parsedWeight,
  };

  return { ok: true, profile };
}

// ===== Message helpers =====
function setTodayMessage(message, isError = false) {
  state.todayMessage = message;
  state.todayMessageIsError = isError;
  renderActivity();
}

function setHistoryMessage(message, isError = false) {
  state.historyMessage = message;
  state.historyMessageIsError = isError;
  if (state.activitySubView === "history") renderHistory();
}

function setSettingsMessage(message, isError = false) {
  state.settingsMessage = message;
  state.settingsMessageIsError = isError;
  els.settingsMessage.textContent = message;
  els.settingsMessage.classList.toggle("error", isError);
}

function setWeatherMessage(message, isError = false) {
  state.weatherMessage = message;
  state.weatherMessageIsError = isError;
  els.weatherMessage.textContent = message;
  els.weatherMessage.classList.toggle("error", isError);
}

// ===== Sensor helpers =====
function supportsDeviceMotion() {
  return typeof window !== "undefined" && "DeviceMotionEvent" in window;
}

function needsMotionPermissionPrompt() {
  return typeof DeviceMotionEvent !== "undefined" && typeof DeviceMotionEvent.requestPermission === "function";
}

async function ensureMotionPermission() {
  if (!supportsDeviceMotion()) return false;
  if (!needsMotionPermissionPrompt()) { runtime.permission = "granted"; return true; }

  try {
    const response = await DeviceMotionEvent.requestPermission();
    runtime.permission = response;
    return response === "granted";
  } catch (_error) {
    runtime.permission = "denied";
    return false;
  }
}

// ===== Formatters =====
function weatherLabel(condition) {
  if (condition === "SUNNY") return "晴れ";
  if (condition === "CLOUDY") return "くもり";
  if (condition === "RAINY") return "雨";
  if (condition === "SNOWY") return "雪";
  return condition;
}

function formatYen(priceYen) {
  return `¥${Number(priceYen).toLocaleString("ja-JP")}`;
}

function formatDistance(distanceMeters) {
  return `${(distanceMeters / 1_000).toFixed(2)} km`;
}

function formatMeters(meters) {
  return `${meters.toFixed(2)} m`;
}

function formatSpeed(speedMps) {
  return `${(speedMps * 3.6).toFixed(2)} km/h`;
}

function formatCalories(caloriesKcal) {
  return `${Math.round(caloriesKcal)} kcal`;
}

function formatDuration(durationMs) {
  const totalSeconds = Math.floor(durationMs / 1_000);
  const hours = Math.floor(totalSeconds / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}:${pad2(minutes)}:${pad2(seconds)}`;
  return `${pad2(minutes)}:${pad2(seconds)}`;
}

function formatDateTime(epochMs) {
  const date = new Date(epochMs);
  return `${date.getMonth() + 1}/${date.getDate()} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

function formatDay(dayEpoch) {
  const date = new Date(dayEpoch * MS_PER_DAY);
  const month = date.getUTCMonth() + 1;
  const day = date.getUTCDate();
  const year = date.getUTCFullYear();
  return year === new Date().getFullYear() ? `${month}/${day}` : `${year}/${month}/${day}`;
}

function formatCsvDate(dayEpoch) {
  const date = new Date(dayEpoch * MS_PER_DAY);
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

function formatCsvDouble(value) {
  return Number(value).toFixed(3);
}

function formatWeightInput(weightKg) {
  return Math.abs(weightKg % 1.0) < 1e-9 ? weightKg.toFixed(0) : weightKg.toFixed(1);
}

function fileTimestamp() {
  const now = new Date();
  return [now.getFullYear(), pad2(now.getMonth() + 1), pad2(now.getDate()), "_",
    pad2(now.getHours()), pad2(now.getMinutes()), pad2(now.getSeconds())].join("");
}

// ===== Utilities =====
function currentDayEpoch() {
  const now = new Date();
  return Math.floor(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()) / MS_PER_DAY);
}

function averageSpeedMps(metrics) {
  if (metrics.movingDurationMs <= 0) return 0;
  return metrics.totalDistanceMeters / (metrics.movingDurationMs / 1_000);
}

function toNumber(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function toInteger(value, fallback) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function pad2(value) {
  return String(value).padStart(2, "0");
}

async function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;
  if (!window.isSecureContext) return;
  try {
    await navigator.serviceWorker.register("./sw.js", { scope: "./" });
  } catch (_error) {
    // Silent fail
  }
}
