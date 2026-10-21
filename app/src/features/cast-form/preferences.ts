import type { MessageInitShape } from "@bufbuild/protobuf";
import type { PreferencesSchema } from "../../gen/castor/v1/cast_pb";

const text = (f: FormData, key: string) => String(f.get(key) ?? "").trim();

function language(value: string): string {
  const code = value.toLowerCase();
  if (!/^[a-z]{2,3}$/.test(code)) throw new Error("Use a two or three letter language code, such as en or fra.");
  return code;
}

// An omitted preference inherits the API server's configuration for this cast.
export function preferences(f: FormData): MessageInitShape<typeof PreferencesSchema> {
  const delivery = text(f, "delivery");
  const height = text(f, "maxHeight");
  const subtitleMode = text(f, "subtitles");
  const audioLanguage = text(f, "audioLanguage");
  const subtitleLanguage = subtitleMode === "language" ? text(f, "subtitleLanguage") : subtitleMode;

  return {
    delivery: delivery ? Number(delivery) : undefined,
    maxHeight: height ? Number(height) : undefined,
    audio: audioLanguage ? { languages: [language(audioLanguage)] } : undefined,
    subtitles: !subtitleMode || subtitleMode === "auto" ? undefined : {
      mode: subtitleMode === "off" ? { case: "disabled", value: {} }
        : { case: "preferences", value: {
          languages: [language(subtitleLanguage)],
          transcribeIfMissing: f.get("transcribeIfMissing") === "on",
        } },
    },
  };
}
