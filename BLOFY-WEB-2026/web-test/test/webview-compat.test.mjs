import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const publicUrl = new URL("../public/", import.meta.url);

test("legacy Android WebView bundle is ES5 and selected by the page", async () => {
  const [bundle, index, worker] = await Promise.all([
    readFile(new URL("app.compat.js", publicUrl), "utf8"),
    readFile(new URL("index.html", publicUrl), "utf8"),
    readFile(new URL("sw.js", publicUrl), "utf8"),
  ]);

  assert.doesNotMatch(bundle, /\?\.|\?\?|=>|\basync\s+function\b/);
  assert.match(bundle, /window\.BlofyRemote/);
  assert.match(index, /app\.compat\.js\?v=202608204/);
  assert.match(index, /<script async src="\/vendor\/hls\.min\.js"><\/script>/);
  assert.match(index, /تعذر بدء واجهة التطبيق/);
  assert.match(worker, /app\.compat\.js\?v=202608204/);
});

test("native back bridge can leave the boot screen", async () => {
  const source = await readFile(new URL("app.js", publicUrl), "utf8");
  assert.match(source, /return goBack\(\)/);
  assert.match(source, /BlofyAndroid\?\.ready\?\.\(\)/);
});
