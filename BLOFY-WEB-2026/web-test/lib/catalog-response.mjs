function signedImage(value, signer) {
  return value ? signer(String(value)) : "";
}

/**
 * Build the public catalog representation without leaking the private source
 * URL kept by M3U snapshots.
 */
export function publicCatalogItem(value, signer) {
  const item = value || {};
  return {
    id: String(item.id ?? ""),
    name: String(item.name || "بدون اسم"),
    image: item.image ? signer(String(item.image), "poster", item) : "",
    backdrop: item.backdrop ? signer(String(item.backdrop), "backdrop", item) : "",
    category: String(item.category || ""),
    categoryId: String(item.categoryId || ""),
    rating: String(item.rating || ""),
    year: String(item.year || ""),
    extension: String(item.extension || ""),
    epgId: String(item.epgId || ""),
    type: String(item.type || ""),
  };
}

/**
 * Sign a series response on a fresh object graph. The normalized object may be
 * held in memory cache, so mutating it would sign an already-signed relative
 * path again on the next request.
 */
export function publicSeriesItem(value, signer) {
  const item = structuredClone(value || {});
  delete item.sourceUrl;
  delete item.direct_source;
  item.image = signedImage(item.image, signer);
  item.backdrop = signedImage(item.backdrop, signer);
  item.seasons = Array.isArray(item.seasons) ? item.seasons : [];
  for (const season of item.seasons) {
    season.episodes = Array.isArray(season.episodes) ? season.episodes : [];
    for (const episode of season.episodes) {
      delete episode.sourceUrl;
      delete episode.direct_source;
      episode.image = signedImage(episode.image, signer);
    }
  }
  return item;
}
