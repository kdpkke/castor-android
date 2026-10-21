import "server-only";
import type { MessageInitShape } from "@bufbuild/protobuf";
import type { SourceSchema } from "@/gen/castor/v1/cast_pb";
export { preferences } from "./preferences";
export { target } from "./target";

const text = (f: FormData, k: string) => String(f.get(k) ?? "").trim();

export const source = (f: FormData): MessageInitShape<typeof SourceSchema> =>
  text(f, "kind") === "pages"
    ? { source: { case: "pages", value: { urls: text(f, "url").split(/\s+/).filter(Boolean) } } }
    : { source: { case: "stream", value: { url: text(f, "url") } } };
