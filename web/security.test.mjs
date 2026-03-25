import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const rootDir = path.dirname(fileURLToPath(import.meta.url));
const appJs = fs.readFileSync(path.join(rootDir, "app.js"), "utf8");
const swJs = fs.readFileSync(path.join(rootDir, "sw.js"), "utf8");
const indexHtml = fs.readFileSync(path.join(rootDir, "index.html"), "utf8");

test("weather errors are collapsed to a generic user-facing message", () => {
  assert.match(appJs, /自動取得に失敗しました。時間をおいて再試行してください。/);
  assert.doesNotMatch(appJs, /自動取得に失敗しました:\s*\$\{error\.message/);
});

test("persistent browser storage is opt-in and session-scoped by default", () => {
  assert.match(appJs, /SESSION_STORAGE_KEY/);
  assert.match(appJs, /LOCAL_STORAGE_KEY/);
  assert.match(appJs, /PERSIST_PREFERENCE_KEY/);
  assert.match(appJs, /window\.sessionStorage/);
  assert.match(indexHtml, /id="persist-opt-in"/);
});

test("service worker can force activation of patched versions", () => {
  assert.match(swJs, /SKIP_WAITING/);
  assert.match(appJs, /controllerchange/);
  assert.match(appJs, /registration\.update\(\)/);
});
