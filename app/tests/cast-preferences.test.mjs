import assert from "node:assert/strict";
import test from "node:test";
import { preferences } from "../src/features/cast-form/preferences.ts";

test("blank playback options inherit the server's preferences", () => {
  assert.deepEqual(preferences(new FormData()), {
    delivery: undefined,
    maxHeight: undefined,
    audio: undefined,
    subtitles: undefined,
  });
});

test("audio, caption, and transcription choices serialize as per-cast preferences", () => {
  const form = new FormData();
  form.set("delivery", "1");
  form.set("maxHeight", "2160");
  form.set("audioLanguage", " FR ");
  form.set("subtitles", "language");
  form.set("subtitleLanguage", " EN ");
  form.set("transcribeIfMissing", "on");
  assert.deepEqual(preferences(form), {
    delivery: 1,
    maxHeight: 2160,
    audio: { languages: ["fr"] },
    subtitles: { mode: { case: "preferences", value: {
      languages: ["en"],
      transcribeIfMissing: true,
    } } },
  });
});

test("turning captions off does not request transcription", () => {
  const form = new FormData();
  form.set("subtitles", "off");
  form.set("transcribeIfMissing", "on");
  assert.deepEqual(preferences(form).subtitles, { mode: { case: "disabled", value: {} } });
});

test("invalid language codes fail before the cast RPC", () => {
  const form = new FormData();
  form.set("audioLanguage", "en-US");
  assert.throws(() => preferences(form), /two or three letter language code/);
  form.delete("audioLanguage");
  form.set("subtitles", "language");
  assert.throws(() => preferences(form), /two or three letter language code/);
});
