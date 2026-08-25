import assert from "node:assert/strict";
import test from "node:test";
import { normalizeTmdbCredits } from "../lib/tmdb.mjs";

test("TMDB credits expose a compact cast, crew, and director payload", () => {
  const result = normalizeTmdbCredits({
    credits: {
      cast: [{ id: 1, name: "Actor", character: "Hero", profile_path: "/actor.jpg" }],
      crew: [
        { id: 3, name: "Writer", job: "Writer", department: "Writing", profile_path: "/writer.jpg" },
        { id: 2, name: "Director", job: "Director", department: "Directing", profile_path: "/director.jpg" },
        { id: 4, name: "Ignored", job: "Thanks", department: "Crew", profile_path: "/ignored.jpg" },
      ],
    },
    created_by: [{ id: 5, name: "Creator", profile_path: "/creator.jpg" }],
  });

  assert.deepEqual(result.cast[0], {
    id: "1", name: "Actor", character: "Hero",
    image: "https://image.tmdb.org/t/p/w185/actor.jpg",
  });
  assert.equal(result.director, "Director");
  assert.deepEqual(result.crew.map((person) => person.job), ["Director", "Writer", "Creator"]);
  assert.equal(result.crew[0].department, "Directing");
  assert.equal(result.crew[0].image, "https://image.tmdb.org/t/p/w185/director.jpg");
  assert.equal(result.crew.some((person) => person.name === "Ignored"), false);
});
