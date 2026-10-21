"use client";

import { useState } from "react";
import { Delivery } from "@/gen/castor/v1/stream_pb";

export function PlaybackOptions() {
  const [delivery, setDelivery] = useState("");
  const [maxHeight, setMaxHeight] = useState("");
  const [subtitleMode, setSubtitleMode] = useState("");
  return <details className="rounded-2xl bg-white/10 p-4 text-sm text-cream">
    <summary className="cursor-pointer font-bold">Playback options</summary>
    <div className="mt-4 grid gap-3">
      <label className="grid gap-1 font-semibold">Delivery
        <select name="delivery" value={delivery} onChange={(event) => setDelivery(event.target.value)} className="rounded-full bg-ink px-4 py-2 text-cream">
          <option value="">Server default</option>
          <option value={Delivery.AUTO}>Automatic (pass through when possible)</option>
          <option value={Delivery.SERVE}>Relay through Castor</option>
        </select>
      </label>
      <label className="grid gap-1 font-semibold">Maximum video height
        <select name="maxHeight" value={maxHeight} onChange={(event) => setMaxHeight(event.target.value)} className="rounded-full bg-ink px-4 py-2 text-cream">
          <option value="">Server default</option>
          <option value="480">480p</option>
          <option value="720">720p</option>
          <option value="1080">1080p</option>
          <option value="2160">2160p (4K)</option>
        </select>
      </label>
      <label className="grid gap-1 font-semibold">Audio language
        <input name="audioLanguage" type="text" maxLength={3} pattern="[A-Za-z]{2,3}" autoCapitalize="none" spellCheck={false} placeholder="Server default (e.g. en)" className="min-w-0 rounded-full bg-ink px-4 py-2 text-cream placeholder:text-muted/60" />
      </label>
      <label className="grid gap-1 font-semibold">Subtitles
        <select name="subtitles" value={subtitleMode} onChange={(event) => setSubtitleMode(event.target.value)} className="rounded-full bg-ink px-4 py-2 text-cream">
          <option value="">Server default</option>
          <option value="off">Off</option>
          <option value="language">Choose a language</option>
        </select>
      </label>
      {subtitleMode === "language" && <>
        <label className="grid gap-1 font-semibold">Subtitle language
          <input name="subtitleLanguage" type="text" required maxLength={3} pattern="[A-Za-z]{2,3}" autoCapitalize="none" spellCheck={false} placeholder="e.g. en" className="min-w-0 rounded-full bg-ink px-4 py-2 text-cream placeholder:text-muted/60" />
        </label>
        <label className="flex items-start gap-2 font-semibold"><input name="transcribeIfMissing" type="checkbox" className="mt-1" />Generate subtitles if the source has none</label>
      </>}
      <p className="text-xs font-normal text-white/60">Language codes use two or three letters, such as en or fra. Blank audio keeps the server’s setting.</p>
      <p className="text-xs font-normal text-white/60">Automatic delivery lets Castor pass through compatible media. When a relay needs to encode, it uses a working hardware encoder if one is available.</p>
    </div>
  </details>;
}
