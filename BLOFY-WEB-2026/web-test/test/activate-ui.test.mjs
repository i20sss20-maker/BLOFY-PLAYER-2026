import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const publicUrl = new URL("../public/", import.meta.url);

test("activation portal presents the BLOFY-XXXX-XXXX device format", async () => {
  const html = await readFile(new URL("activate.html", publicUrl), "utf8");

  assert.match(html, /id="deviceId"[^>]*maxlength="15"/);
  assert.match(html, /id="deviceId"[^>]*pattern="BLOFY-\(\[A-Za-z0-9\]\{2\}\|\[A-Za-z0-9\]\{4\}-\[A-Za-z0-9\]\{4\}\)"/);
  assert.match(html, /id="deviceId"[^>]*placeholder="BLOFY-66HL-GB09"/);
  assert.match(html, /id="pairingCode"[^>]*pattern="\[0-9\]\{6\}"[^>]*maxlength="6"/);
});

test("manual login and QR prefill share the public device validator with temporary legacy support", async () => {
  const script = await readFile(new URL("activate.js", publicUrl), "utf8");
  const literal = script.match(/const DISPLAY_ID_PATTERN = \/\^(.*?)\$\//);
  const legacyLiteral = script.match(/const LEGACY_DISPLAY_ID_PATTERN = \/\^(.*?)\$\//);

  assert.ok(literal, "DISPLAY_ID_PATTERN must remain explicit and testable");
  assert.ok(legacyLiteral, "legacy device ids must remain explicit while v323 devices are active");
  const pattern = new RegExp(`^${literal[1]}$`);
  const legacyPattern = new RegExp(`^${legacyLiteral[1]}$`);
  for (const value of ["BLOFY-66HL-GB09", "BLOFY-0000-ZZZZ", "BLOFY-A1B2-C3D4"]) {
    assert.equal(pattern.test(value), true, `${value} should be accepted`);
  }
  assert.equal(legacyPattern.test("BLOFY-SV"), true, "current v323 device ids remain accepted");
  for (const value of ["BLOFY-S", "BLOFY-SV7", "BLOFY-66HL-GB0", "BLOFY-66HL-GB090", "blofy-66hl-gb09", "BLOFY-66H!-GB09"]) {
    assert.equal(pattern.test(value), false, `${value} should be rejected before normalization`);
    assert.equal(legacyPattern.test(value), false, `${value} should not pass the legacy validator`);
  }

  assert.match(script, /isValidDisplayId\(deviceId\)/);
  assert.match(script, /isValidDisplayId\(queryDevice\)/);
});
