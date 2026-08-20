import crypto from "node:crypto";

function attributes(line) {
  const out = {};
  const body = line.slice(line.indexOf(":") + 1, line.lastIndexOf(","));
  const matcher = /([\w-]+)=(?:"([^"]*)"|([^\s,]+))/g;
  let match;
  while ((match = matcher.exec(body))) out[match[1].toLowerCase()] = match[2] ?? match[3] ?? "";
  return out;
}

function kindFor(group, url) {
  const value = `${group} ${url}`.toLowerCase();
  if (/\/series\/|series|مسلسل|مسلسلات/.test(value)) return "series";
  if (/\/movie\/|vod|cinema|movie|film|افلام|أفلام/.test(value)) return "movies";
  return "live";
}

function extensionFor(url, type) {
  try {
    const match = new URL(url).pathname.match(/\.([a-zA-Z0-9]{2,6})$/);
    return match?.[1]?.toLowerCase() || (type === "live" ? "m3u8" : "mp4");
  } catch {
    return type === "live" ? "m3u8" : "mp4";
  }
}

export function parseM3u(text) {
  const lines = text.replace(/^\uFEFF/, "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const items = [];
  let meta = null;
  for (const line of lines) {
    if (line.startsWith("#EXTINF")) {
      const attrs = attributes(line);
      meta = {
        name: line.slice(line.lastIndexOf(",") + 1).trim() || "بدون اسم",
        category: attrs["group-title"] || "غير مصنف",
        image: attrs["tvg-logo"] || "",
        epgId: attrs["tvg-id"] || "",
      };
      continue;
    }
    if (!line.startsWith("#") && meta) {
      const id = crypto.createHash("sha1").update(line).digest("hex").slice(0, 16);
      const type = kindFor(meta.category, line);
      items.push({ id, ...meta, sourceUrl: line, type, extension: extensionFor(line, type) });
      meta = null;
    }
  }
  return items;
}

export function pageItems(items, { category = "", search = "", page = 1, pageSize = 60 } = {}) {
  const needle = String(search).trim().toLocaleLowerCase("ar");
  const filtered = items.filter((item) => (!category || item.category === category || item.categoryId === category) &&
    (!needle || item.name.toLocaleLowerCase("ar").includes(needle)));
  const start = (Math.max(1, Number(page)) - 1) * pageSize;
  return { items: filtered.slice(start, start + pageSize), total: filtered.length, page: Math.max(1, Number(page)), pageSize };
}
