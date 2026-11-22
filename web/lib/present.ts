import type { HistoryEvent } from "@/lib/api";

export function formatScore(score: number | string | null | undefined): string {
  if (score === null || score === undefined) {
    return "none";
  }
  const numeric = typeof score === "number" ? score : Number(score);
  if (Number.isNaN(numeric)) {
    return String(score);
  }
  return numeric.toFixed(3);
}

export function versionOf(value: unknown): number | null {
  if (!value || typeof value !== "object" || !("version" in value)) {
    return null;
  }
  const version = (value as { version?: unknown }).version;
  return typeof version === "number" ? version : null;
}

export function outboxLine(json: unknown): string {
  if (json && typeof json === "object") {
    const record = json as Record<string, unknown>;
    if ("outbox" in record) {
      return JSON.stringify(record.outbox);
    }
    if ("outboxEvent" in record) {
      return JSON.stringify(record.outboxEvent);
    }
    if (record.eventType === "MatchConfirmed") {
      return "MatchConfirmed";
    }
  }
  return "This response does not include an outbox event.";
}

export function versionLine(json: unknown, fallback?: number | null): string {
  const direct = versionOf(json);
  if (direct !== null) {
    return String(direct);
  }
  if (typeof fallback === "number") {
    return String(fallback);
  }
  return "This response does not include a version.";
}

export function eventVersion(event: HistoryEvent): string {
  const after = versionOf(event.after);
  if (after !== null) {
    return String(after);
  }
  const before = versionOf(event.before);
  if (before !== null) {
    return String(before);
  }
  return "not included";
}

export function formatWhen(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("en", {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    second: "2-digit",
  }).format(date);
}

export function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return "Something went wrong talking to the API.";
}
