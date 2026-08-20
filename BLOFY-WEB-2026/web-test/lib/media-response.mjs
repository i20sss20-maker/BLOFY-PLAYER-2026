import { Readable } from "node:stream";

function timedRead(reader, timeoutMs = 3500) {
  let timer;
  return Promise.race([
    reader.read(),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(Object.assign(new Error("مصدر التشغيل لم يرسل بيانات بالسرعة المطلوبة."), { name: "AbortError" })), timeoutMs);
    }),
  ]).finally(() => clearTimeout(timer));
}

export async function inspectPlaylistBody(response) {
  if (!response.body) return { playlist: null, prefix: Buffer.alloc(0), reader: null };
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  while (total < 96) {
    let part;
    try { part = await timedRead(reader, 2500); } catch (error) { await reader.cancel().catch(() => {}); throw error; }
    if (part.done) break;
    const bytes = Buffer.from(part.value);
    chunks.push(bytes);
    total += bytes.length;
    const sample = Buffer.concat(chunks, total).toString("utf8").replace(/^\uFEFF/, "").trimStart();
    if (sample.startsWith("#EXTM3U")) {
      while (total <= 2_000_000) {
        let next;
        try { next = await timedRead(reader, 4000); } catch (error) { await reader.cancel().catch(() => {}); throw error; }
        if (next.done) return { playlist: Buffer.concat(chunks, total).toString("utf8"), prefix: null, reader: null };
        const bytesNext = Buffer.from(next.value);
        chunks.push(bytesNext);
        total += bytesNext.length;
      }
      await reader.cancel().catch(() => {});
      throw new Error("قائمة HLS أكبر من الحد المسموح.");
    }
    if (sample && !"#EXTM3U".startsWith(sample)) break;
  }
  return { playlist: null, prefix: Buffer.concat(chunks, total), reader };
}

export function pipeInspectedBody(res, reader, prefix) {
  if (!reader) return res.end(prefix || undefined);
  const stream = Readable.from((async function* streamRemote() {
    if (prefix?.length) yield prefix;
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      yield Buffer.from(next.value);
    }
  })());
  stream.on("error", () => res.destroy()).pipe(res);
}
