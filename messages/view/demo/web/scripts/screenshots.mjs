import { spawn } from "node:child_process";
import { mkdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const __dirname = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(__dirname, "../../../screenshots/web");
const manifest = JSON.parse(readFileSync(resolve(__dirname, "../public/bundles/manifest.json"), "utf8"));

const PORT = 4174;
const BASE = `http://localhost:${PORT}`;

async function waitForServer(url, timeoutMs = 20000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`server did not become ready at ${url}`);
}

const server = spawn("npx", ["vite", "preview", "--port", String(PORT), "--strictPort"], {
  cwd: resolve(__dirname, ".."),
  stdio: "inherit",
});

try {
  await waitForServer(`${BASE}/bundles/manifest.json`);
  mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 2 });
  for (const { name } of manifest.fixtures) {
    await page.goto(`${BASE}/?only=${name}`);
    const el = page.locator(`#fixture-${name}`);
    await el.waitFor({ state: "visible" });
    await el.screenshot({ path: resolve(outDir, `${name}.png`) });
    console.log(`web screenshot: ${name}`);
  }
  await browser.close();
} finally {
  server.kill("SIGTERM");
}
