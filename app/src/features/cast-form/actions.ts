"use server";

import { authorize } from "@/lib/authorize";

import { redirect } from "next/navigation";
import { describe } from "@/lib/errors";
import { casts } from "@/lib/server";
import { preferences, source, target } from "./request";

export type FormState = { error?: string; targetKey?: string; kind?: string; url?: string };

export async function submitAction(_: FormState, f: FormData): Promise<FormState> {
  let castId: string;
  try {
    await authorize();
    ({ castId } = await casts.cast({
      target: target(f),
      source: source(f),
      preferences: preferences(f),
    }));
  } catch (e) {
    return { error: describe(e), targetKey: String(f.get("targetKey") ?? ""), kind: String(f.get("kind") ?? ""), url: String(f.get("url") ?? "") };
  }
  redirect(`/casts/${encodeURIComponent(castId)}`);
}
