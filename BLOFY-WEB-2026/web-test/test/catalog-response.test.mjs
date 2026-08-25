import assert from "node:assert/strict";
import test from "node:test";
import { publicCatalogItem, publicSeriesItem } from "../lib/catalog-response.mjs";
import { signResource, verifyResource } from "../lib/security.mjs";

function signedPath(raw) {
  const token = signResource(raw, 600);
  return `/api/proxy?u=${encodeURIComponent(token.encoded)}&e=${token.expires}&s=${encodeURIComponent(token.signature)}`;
}

function originalUrl(path) {
  const url = new URL(path, "https://blofy.example");
  return verifyResource(url.searchParams.get("u"), url.searchParams.get("e"), url.searchParams.get("s"));
}

test("public catalog strips the private M3U source while preserving a signed image", () => {
  const raw = {
    id: "channel-1",
    name: "Channel",
    sourceUrl: "https://provider.example/live/user/password/1.ts",
    direct_source: "https://provider.example/live/user/password/1.ts",
    password: "private-password",
    image: "https://provider.example/images/1.png",
  };
  const result = publicCatalogItem(raw, signedPath);
  assert.equal(Object.hasOwn(result, "sourceUrl"), false);
  assert.equal(Object.hasOwn(result, "direct_source"), false);
  assert.equal(Object.hasOwn(result, "password"), false);
  assert.equal(JSON.stringify(result).includes("private-password"), false);
  assert.equal(originalUrl(result.image), raw.image);
  assert.equal(raw.sourceUrl.includes("password"), true);
});

test("native catalog can preserve provider artwork without exposing media credentials", () => {
  const raw = {
    id: "movie-7",
    name: "Movie",
    sourceUrl: "http://provider.example/movie/user/private/7.mp4",
    image: "http://cdn.example/posters/7.jpg",
    backdrop: "http://cdn.example/backdrops/7.jpg",
    rating: "8.4",
    ratingSource: "TMDB",
    releaseDate: "2026-08-24",
    updatedAt: "2026-08-25",
  };
  const result = publicCatalogItem(raw, (value) => value);
  assert.equal(result.image, raw.image);
  assert.equal(result.backdrop, raw.backdrop);
  assert.equal(result.ratingSource, "TMDB");
  assert.equal(result.releaseDate, "2026-08-24");
  assert.equal(result.updatedAt, "2026-08-25");
  assert.equal(Object.hasOwn(result, "sourceUrl"), false);
  assert.equal(JSON.stringify(result).includes("/user/private/"), false);
});

test("repeated series serialization never mutates or re-signs the cached graph", () => {
  const cached = {
    id: "series-1",
    image: "https://provider.example/series/poster.jpg",
    backdrop: "https://provider.example/series/backdrop.jpg",
    metadata: { cast: ["One", "Two"] },
    seasons: [{
      season: "1",
      episodes: [{
        id: "episode-1",
        image: "https://provider.example/episodes/1.jpg",
        sourceUrl: "https://provider.example/series/private-user/private-password/1.mp4",
      }],
    }],
  };

  const first = publicSeriesItem(cached, signedPath);
  const second = publicSeriesItem(cached, signedPath);

  assert.notEqual(first, cached);
  assert.notEqual(first.seasons, cached.seasons);
  assert.notEqual(first.seasons[0].episodes, cached.seasons[0].episodes);
  assert.notEqual(first.metadata, cached.metadata);
  assert.notEqual(first.metadata.cast, cached.metadata.cast);
  assert.equal(cached.image, "https://provider.example/series/poster.jpg");
  assert.equal(cached.seasons[0].episodes[0].image, "https://provider.example/episodes/1.jpg");
  assert.equal(originalUrl(first.image), cached.image);
  assert.equal(originalUrl(second.image), cached.image);
  assert.equal(originalUrl(first.seasons[0].episodes[0].image), cached.seasons[0].episodes[0].image);
  assert.equal(originalUrl(second.seasons[0].episodes[0].image), cached.seasons[0].episodes[0].image);
  assert.equal(Object.hasOwn(first.seasons[0].episodes[0], "sourceUrl"), false);
  assert.equal(JSON.stringify(first).includes("private-password"), false);
  assert.equal(cached.seasons[0].episodes[0].sourceUrl.includes("private-password"), true);
});
