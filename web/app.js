const STORAGE_KEY = "pedometer_web_state_v1";
const MS_PER_DAY = 86_400_000;
const HISTORY_LIMIT = 365;

const CADENCE_WINDOW_MS = 12_000;
const BRISK_CADENCE_SPM = 120.0;
const RUNNING_CADENCE_SPM = 150.0;
const MIN_STEP_GAP_MS = 200;
const MAX_STEP_GAP_MS = 3_000;

const MAX_HEIGHT_CM = 220;
const MIN_HEIGHT_CM = 120;
const MIN_STRIDE_SCALE = 0.7;
const MAX_STRIDE_SCALE = 1.3;
const MIN_WEIGHT_KG = 30.0;
const MAX_WEIGHT_KG = 200.0;

const state = loadState();
state.activeTab = "today";
state.todayMessage = "";
state.historyMessage = "";
state.settingsMessage = "";
state.settingsMessageIsError = false;

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

const els = {
  tabButtons: document.querySelectorAll(".tab-button"),
  tabPanels: document.querySelectorAll("[data-tab-panel]"),

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

  inputHeight: document.getElementById("input-height"),
  inputWeight: document.getElementById("input-weight"),
  inputSex: document.getElementById("input-sex"),
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
  clearLocalDataButton: document.getElementById("clear-local-data"),
  settingsMessage: document.getElementById("settings-message"),
};

bindEvents();
ensureCurrentDay();
renderAll();
registerServiceWorker();

function bindEvents() {
  els.tabButtons.forEach((button) => {
    button.addEventListener("click", () => {
      const nextTab = button.dataset.tab;
      if (!nextTab) return;
      setActiveTab(nextTab);
    });
  });

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
    renderToday();
    setTodayMessage("今日のデータをリセットしました。");
  });

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

  [els.inputHeight, els.inputWeight, els.inputSex, els.inputStrideScale].forEach((input) => {
    input.addEventListener("input", () => {
      renderSettingsPreview();
    });
  });

  els.recalcScaleButton.addEventListener("click", () => {
    recalculateStrideScaleFromCalibration();
  });

  els.saveSettingsButton.addEventListener("click", () => {
    saveSettings();
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

function setActiveTab(tab) {
  state.activeTab = tab;

  els.tabButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.tab === tab);
  });

  els.tabPanels.forEach((panel) => {
    panel.classList.toggle("is-hidden", panel.dataset.tabPanel !== tab);
  });

  if (tab === "history") {
    renderHistory();
  }

  if (tab === "settings") {
    renderSettingsPreview();
  }
}

async function startTracking() {
  if (runtime.startInProgress) return;
  ensureCurrentDay();
  runtime.startInProgress = true;
  renderToday();

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

    runtime.motionListener = (event) => {
      handleDeviceMotion(event);
    };
    window.addEventListener("devicemotion", runtime.motionListener, { passive: true });

    state.sensorSupported = true;
    state.isTracking = true;
    setTodayMessage("計測を開始しました。");
    schedulePersist(true);
  } finally {
    runtime.startInProgress = false;
    renderToday();
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
  renderToday();
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
  const refractoryMs = 250;
  const isPeak = filtered > threshold && runtime.prevFiltered <= threshold;
  const elapsedSincePeak = nowMs - runtime.lastPeakMs;

  if (isPeak && elapsedSincePeak >= refractoryMs) {
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
    briskDistanceMeters:
      previous.briskDistanceMeters + (zone === "BRISK" ? strideMeters : 0),
    briskDurationMs:
      previous.briskDurationMs + (zone === "BRISK" ? movementDeltaMs : 0),
    runningDistanceMeters:
      previous.runningDistanceMeters + (zone === "RUNNING" ? strideMeters : 0),
    runningDurationMs:
      previous.runningDurationMs + (zone === "RUNNING" ? movementDeltaMs : 0),
    lastUpdatedEpochMs: timestampMs,
  };

  schedulePersist();
  renderToday();

  if (state.activeTab === "history") {
    renderHistory();
  }
}

function calculateMovementDelta(nowMs) {
  if (runtime.lastStepTimestampMs == null) {
    runtime.lastStepTimestampMs = nowMs;
    return 0;
  }

  const delta = nowMs - runtime.lastStepTimestampMs;
  runtime.lastStepTimestampMs = nowMs;

  if (delta < MIN_STEP_GAP_MS || delta > MAX_STEP_GAP_MS) {
    return 0;
  }

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
  if (normalized.weightKg != null) {
    return normalized.weightKg;
  }

  const heightMeters = normalized.heightCm / 100;
  let bmi = 22.0;
  if (normalized.sex === "MALE") bmi = 22.5;
  if (normalized.sex === "FEMALE") bmi = 21.5;

  return clamp(bmi * heightMeters * heightMeters, 40.0, 120.0);
}

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

function renderAll() {
  setActiveTab(state.activeTab);
  renderToday();
  renderHistory();
  renderSettingsFromState();
}

function renderToday() {
  const metrics = state.metrics;

  const statusText = state.isTracking ? "計測中" : "停止中";
  els.trackingStatus.textContent = statusText;
  els.trackingStatus.classList.toggle("stopped", !state.isTracking);

  let sensorStatusText = "センサー利用可";
  if (!supportsDeviceMotion()) {
    sensorStatusText = "このブラウザはモーションセンサー非対応です";
  } else if (runtime.permission !== "granted" && needsMotionPermissionPrompt()) {
    sensorStatusText = "iOSでは開始時にモーション許可が必要です";
  } else if (!state.sensorSupported) {
    sensorStatusText = "モーション権限またはセンサー入力が利用できません";
  }
  els.sensorStatus.textContent = sensorStatusText;

  els.startButton.disabled = state.isTracking || runtime.startInProgress;
  els.stopButton.disabled = !state.isTracking;

  els.metricSteps.textContent = `${metrics.steps} 歩`;
  els.metricDistance.textContent = formatDistance(metrics.totalDistanceMeters);
  els.metricSpeed.textContent = formatSpeed(averageSpeedMps(metrics));
  els.metricCalories.textContent = formatCalories(metrics.totalCaloriesKcal);
  els.metricBriskDuration.textContent = formatDuration(metrics.briskDurationMs);
  els.metricBriskDistance.textContent = formatDistance(metrics.briskDistanceMeters);
  els.metricRunningDuration.textContent = formatDuration(metrics.runningDurationMs);
  els.metricRunningDistance.textContent = formatDistance(metrics.runningDistanceMeters);

  if (metrics.lastUpdatedEpochMs > 0) {
    els.lastUpdated.textContent = `最終更新: ${formatDateTime(metrics.lastUpdatedEpochMs)}`;
  } else {
    els.lastUpdated.textContent = "";
  }

  els.todayMessage.textContent = state.todayMessage;
  els.todayMessage.classList.toggle("error", state.todayMessageIsError === true);
}

function renderHistory() {
  const filtered = filteredHistory(state.history, state.historyRangeDays);
  const weekly = summarizeLastDays(state.history, 7);
  const monthly = summarizeLastDays(state.history, 30);

  els.filterButtons.forEach((button) => {
    const raw = button.dataset.historyDays;
    const buttonDays = raw === "all" ? null : Number.parseInt(raw ?? "", 10);
    const isActive = buttonDays === state.historyRangeDays;
    button.classList.toggle("is-active", isActive);
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
  const maxSteps = Math.max(...sortedAsc.map((day) => day.steps), 1);

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

    const dateCell = document.createElement("td");
    dateCell.textContent = formatDay(day.dayEpoch);

    const stepsCell = document.createElement("td");
    stepsCell.textContent = `${day.steps} 歩`;

    const distanceCell = document.createElement("td");
    distanceCell.textContent = formatDistance(day.totalDistanceMeters);

    const caloriesCell = document.createElement("td");
    caloriesCell.textContent = formatCalories(day.totalCaloriesKcal);

    row.appendChild(dateCell);
    row.appendChild(stepsCell);
    row.appendChild(distanceCell);
    row.appendChild(caloriesCell);
    els.historyTableBody.appendChild(row);
  });
}

function renderSettingsFromState() {
  const profile = state.profile;

  els.inputHeight.value = String(profile.heightCm);
  els.inputWeight.value = profile.weightKg == null ? "" : formatWeightInput(profile.weightKg);
  els.inputSex.value = profile.sex;
  els.inputStrideScale.value = String(profile.strideScale);

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

function recalculateStrideScaleFromCalibration() {
  const steps = Number.parseInt(els.inputCalibrationSteps.value, 10);
  const distanceMeters = Number.parseFloat(els.inputCalibrationDistance.value);

  if (!Number.isFinite(steps) || steps <= 0 || !Number.isFinite(distanceMeters) || distanceMeters <= 0) {
    setSettingsMessage("実測歩数と実測距離を正しく入力してください。", true);
    return;
  }

  const profile = parseProfileFromInputs(false).profile;
  const baseWalk = estimateStride({ ...profile, strideScale: 1.0 }).walkMeters;
  const recalculatedScale = clamp((distanceMeters / steps) / baseWalk, MIN_STRIDE_SCALE, MAX_STRIDE_SCALE);

  els.inputStrideScale.value = recalculatedScale.toFixed(2);
  renderSettingsPreview();
  setSettingsMessage("補正係数を再計算しました。必要なら保存してください。", false);
}

function saveSettings() {
  const parsed = parseProfileFromInputs(true);
  if (!parsed.ok) {
    setSettingsMessage(parsed.errorMessage, true);
    return;
  }

  state.profile = normalizeProfile(parsed.profile);
  schedulePersist(true);

  setSettingsMessage("設定を保存しました。", false);
  renderSettingsPreview();
}

function clearLocalData() {
  if (!window.confirm("保存済みの設定と履歴データを削除しますか？")) return;

  stopTracking();
  state.profile = normalizeProfile();
  state.metrics = newMetrics(currentDayEpoch());
  state.history = [];
  state.historyRangeDays = null;
  state.sensorSupported = true;
  resetCadenceRuntime();

  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch (_error) {
    // Ignore local storage errors and keep in-memory reset.
  }

  setHistoryMessage("", false);
  setTodayMessage("");
  setSettingsMessage("保存データを削除しました。", false);
  schedulePersist(true);
  renderAll();
}

function parseProfileFromInputs(strictValidation) {
  const heightRaw = els.inputHeight.value.trim();
  const weightRaw = els.inputWeight.value.trim();
  const sexRaw = els.inputSex.value;
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
    strideScale: Number.isFinite(strideScaleRaw)
      ? clamp(strideScaleRaw, MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
      : fallback.strideScale,
    weightKg: parsedWeight,
  };

  return { ok: true, profile };
}

async function exportCsv(share) {
  const rows = filteredHistory(state.history, state.historyRangeDays);
  if (rows.length === 0) {
    setHistoryMessage("履歴データがないためCSVを出力できません。", true);
    return;
  }

  const csv = toCsv(rows);
  const filename = `pedometer_history_${fileTimestamp()}.csv`;

  if (!share) {
    triggerCsvDownload(csv, filename);
    setHistoryMessage("CSVを保存しました。", false);
    return;
  }

  const file = new File([csv], filename, { type: "text/csv" });
  if (navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({
        title: "Pedometer history CSV",
        files: [file],
      });
      setHistoryMessage("CSV共有シートを開きました。", false);
    } catch (error) {
      if (error && error.name === "AbortError") {
        return;
      }
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

  // Browser keeps object URLs alive until revoked.
  window.setTimeout(() => {
    URL.revokeObjectURL(url);
  }, 60_000);
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

function filteredHistory(history, days) {
  const sorted = [...history].sort((a, b) => b.dayEpoch - a.dayEpoch);
  if (days == null) return sorted;

  const clampedDays = Math.max(days, 1);
  const today = currentDayEpoch();
  const minDay = today - (clampedDays - 1);

  return sorted.filter((day) => day.dayEpoch >= minDay && day.dayEpoch <= today);
}

function summarizeLastDays(history, days) {
  const filtered = filteredHistory(history, days);

  return {
    steps: filtered.reduce((sum, day) => sum + day.steps, 0),
    distanceMeters: filtered.reduce((sum, day) => sum + day.totalDistanceMeters, 0),
    caloriesKcal: filtered.reduce((sum, day) => sum + day.totalCaloriesKcal, 0),
  };
}

function loadState() {
  const fallback = {
    profile: normalizeProfile(),
    metrics: newMetrics(currentDayEpoch()),
    history: [],
    historyRangeDays: null,
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

  if (!parsed || typeof parsed !== "object") {
    return fallback;
  }

  const loaded = {
    profile: normalizeProfile(parsed.profile),
    metrics: normalizeMetrics(parsed.metrics),
    history: normalizeHistory(parsed.history),
    historyRangeDays: normalizeHistoryRangeDays(parsed.historyRangeDays),
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
    if (persistTimer) {
      clearTimeout(persistTimer);
      persistTimer = 0;
    }
    persistState();
    return;
  }

  if (persistTimer) return;

  persistTimer = window.setTimeout(() => {
    persistTimer = 0;
    persistState();
  }, 800);
}

function persistState() {
  const data = {
    profile: state.profile,
    metrics: state.metrics,
    history: state.history,
    historyRangeDays: state.historyRangeDays,
  };

  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  } catch (_error) {
    setTodayMessage("ローカル保存に失敗しました。ブラウザ容量を確認してください。", true);
  }
}

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
    const numericWeight = Number(source.weightKg);
    if (Number.isFinite(numericWeight)) {
      weightKg = clamp(numericWeight, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
    }
  }

  return {
    heightCm,
    sex,
    strideScale,
    weightKg,
  };
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
  const filtered = history.filter((entry) => entry.dayEpoch !== day.dayEpoch);
  const next = [day, ...filtered].sort((a, b) => b.dayEpoch - a.dayEpoch);
  return next.slice(0, HISTORY_LIMIT);
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

function currentDayEpoch() {
  const now = new Date();
  const utc = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.floor(utc / MS_PER_DAY);
}

function averageSpeedMps(metrics) {
  if (metrics.movingDurationMs <= 0) return 0;
  return metrics.totalDistanceMeters / (metrics.movingDurationMs / 1_000);
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

  if (hours > 0) {
    return `${hours}:${pad2(minutes)}:${pad2(seconds)}`;
  }

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
  const currentYear = new Date().getFullYear();

  if (year === currentYear) {
    return `${month}/${day}`;
  }

  return `${year}/${month}/${day}`;
}

function formatCsvDate(dayEpoch) {
  const date = new Date(dayEpoch * MS_PER_DAY);
  const year = date.getUTCFullYear();
  const month = pad2(date.getUTCMonth() + 1);
  const day = pad2(date.getUTCDate());
  return `${year}-${month}-${day}`;
}

function formatCsvDouble(value) {
  return Number(value).toFixed(3);
}

function formatWeightInput(weightKg) {
  if (Math.abs(weightKg % 1.0) < 1e-9) {
    return weightKg.toFixed(0);
  }
  return weightKg.toFixed(1);
}

function fileTimestamp() {
  const now = new Date();
  return [
    now.getFullYear(),
    pad2(now.getMonth() + 1),
    pad2(now.getDate()),
    "_",
    pad2(now.getHours()),
    pad2(now.getMinutes()),
    pad2(now.getSeconds()),
  ].join("");
}

function setTodayMessage(message, isError = false) {
  state.todayMessage = message;
  state.todayMessageIsError = isError;
  renderToday();
}

function setHistoryMessage(message, isError = false) {
  state.historyMessage = message;
  state.historyMessageIsError = isError;
  if (state.activeTab === "history") {
    renderHistory();
  }
}

function setSettingsMessage(message, isError = false) {
  state.settingsMessage = message;
  state.settingsMessageIsError = isError;
  els.settingsMessage.textContent = message;
  els.settingsMessage.classList.toggle("error", isError);
}

function supportsDeviceMotion() {
  return typeof window !== "undefined" && "DeviceMotionEvent" in window;
}

function needsMotionPermissionPrompt() {
  return (
    typeof DeviceMotionEvent !== "undefined" &&
    typeof DeviceMotionEvent.requestPermission === "function"
  );
}

async function ensureMotionPermission() {
  if (!supportsDeviceMotion()) return false;

  if (!needsMotionPermissionPrompt()) {
    runtime.permission = "granted";
    return true;
  }

  try {
    const response = await DeviceMotionEvent.requestPermission();
    runtime.permission = response;
    return response === "granted";
  } catch (_error) {
    runtime.permission = "denied";
    return false;
  }
}

function toNumber(value, fallback) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : fallback;
}

function toInteger(value, fallback) {
  const integer = Number.parseInt(value, 10);
  return Number.isFinite(integer) ? integer : fallback;
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
    // Silent fail: app works without service worker.
  }
}
