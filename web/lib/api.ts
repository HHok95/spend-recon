/**
 * The only HTTP client for the demo.
 *
 * Switch [DEMO_SESSION_HEADER] if the sandbox reads the session token from
 * X-Demo-Session instead of Authorization: Bearer.
 *
 * POST /demo/session and GET /demo/export are not on main yet. With
 * NEXT_PUBLIC_DEMO_MOCK=1, and only while NODE_ENV is not production, those
 * two calls return a stand-in. Production never does.
 */

export const DEMO_SESSION_HEADER = "Authorization" as "Authorization" | "X-Demo-Session";

const MISSING_URL = "NEXT_PUBLIC_API_URL is not set.";

export class ApiError extends Error {
  readonly status: number;
  readonly retryAfter: string | null;

  constructor(message: string, status: number, retryAfter: string | null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.retryAfter = retryAfter;
  }
}

export function isSlowDown(error: unknown): boolean {
  return error instanceof ApiError && error.status === 429;
}

export type Session = {
  tenantId: string;
  token: string;
  expiresAt: string;
};

export type TransactionCreated = {
  id: string;
  status: string;
};

export type DocumentCreated = {
  id: string;
};

export type ReconcileResult = {
  matchId: string;
  status: string;
  score: number | string | null;
  rulesFired: string[];
};

export type Match = {
  matchId: string;
  transactionId: string;
  documentId: string | null;
  status: string;
  score: number | string | null;
  rulesFired: string[];
  exceptionType: string | null;
  version: number;
  decidedBy: string | null;
  decidedAt: string | null;
  createdAt: string;
};

export type Decision = {
  matchId: string;
  status: string;
  version: number;
};

export type HistoryEvent = {
  id: number;
  eventType: string;
  actor: string;
  reason: string | null;
  rulesFired: string[];
  before: unknown;
  after: unknown;
  createdAt: string;
};

export type CallResult = {
  status: number;
  raw: string;
  json: unknown;
  retryAfter: string | null;
  idempotencyKey: string | null;
};

export type ExportResult = {
  csv: string;
  mocked: boolean;
  status: number;
  retryAfter: string | null;
};

type ChargeBody = {
  cardOwnerId: string;
  merchant: string;
  amount: string;
  currency: string;
  txnDate: string;
};

type ReceiptBody = {
  kind: "RECEIPT";
  cardOwnerId: string;
  merchant: string;
  amount: string;
  currency: string;
  docDate: string;
};

export type PresetBook = {
  charge: ChargeBody;
  receipt: ReceiptBody;
};

/** Identical merchant, amount, currency, and date, with no tax. The score stays at 0.700, so the match is suggested and a person can still approve it. */
export function presetBook(): PresetBook {
  const cardOwnerId = `demo-${crypto.randomUUID()}`;
  const date = new Date().toISOString().slice(0, 10);
  return {
    charge: {
      cardOwnerId,
      merchant: "Northwind Coffee",
      amount: "18.40",
      currency: "AUD",
      txnDate: date,
    },
    receipt: {
      kind: "RECEIPT",
      cardOwnerId,
      merchant: "Northwind Coffee",
      amount: "18.40",
      currency: "AUD",
      docDate: date,
    },
  };
}

export function chargeJson(charge: ChargeBody): string {
  return JSON.stringify({
    cardOwnerId: charge.cardOwnerId,
    merchant: charge.merchant,
    amount: charge.amount,
    currency: charge.currency,
    txnDate: charge.txnDate,
  });
}

export function receiptJson(receipt: ReceiptBody): string {
  return JSON.stringify({
    kind: receipt.kind,
    cardOwnerId: receipt.cardOwnerId,
    merchant: receipt.merchant,
    amount: receipt.amount,
    currency: receipt.currency,
    docDate: receipt.docDate,
  });
}

export function approveJson(version: number): string {
  return JSON.stringify({
    version,
    actor: "demo-reviewer",
    reason: "Receipt matches the charge.",
  });
}

function baseUrl(): string {
  const configured = process.env.NEXT_PUBLIC_API_URL?.trim();
  if (!configured) {
    throw new ApiError(MISSING_URL, 0, null);
  }
  return configured.replace(/\/$/, "");
}

function mocksAllowed(): boolean {
  return process.env.NODE_ENV !== "production" && process.env.NEXT_PUBLIC_DEMO_MOCK === "1";
}

function applySession(headers: Headers, token: string) {
  if (DEMO_SESSION_HEADER === "Authorization") {
    headers.set("Authorization", `Bearer ${token}`);
    return;
  }
  headers.set("X-Demo-Session", token);
}

function detailOf(json: unknown, fallback: string): string {
  if (json && typeof json === "object" && "detail" in json) {
    const detail = (json as { detail?: unknown }).detail;
    if (typeof detail === "string" && detail.trim()) {
      return detail;
    }
  }
  return fallback;
}

async function send(
  path: string,
  init: {
    method: string;
    body?: string;
    idempotencyKey?: string;
    session?: Session | null;
    accept?: string;
  },
): Promise<CallResult> {
  const headers = new Headers();
  if (init.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (init.accept) {
    headers.set("Accept", init.accept);
  }
  if (init.idempotencyKey) {
    headers.set("Idempotency-Key", init.idempotencyKey);
  }
  if (init.session) {
    applySession(headers, init.session.token);
  }
  const devKey = process.env.NEXT_PUBLIC_DEV_API_KEY;
  if (devKey) {
    headers.set("X-API-Key", devKey);
  }

  let response: Response;
  try {
    response = await fetch(`${baseUrl()}${path}`, {
      method: init.method,
      headers,
      body: init.body,
      cache: "no-store",
    });
  } catch {
    throw new ApiError("The API could not be reached.", 0, null);
  }

  const raw = await response.text();
  let json: unknown = null;
  if (raw) {
    try {
      json = JSON.parse(raw) as unknown;
    } catch {
      json = null;
    }
  }
  const retryAfter = response.headers.get("Retry-After");
  if (response.status === 429) {
    throw new ApiError("Slow down. The API is limiting requests.", 429, retryAfter);
  }
  return {
    status: response.status,
    raw,
    json,
    retryAfter,
    idempotencyKey: init.idempotencyKey ?? null,
  };
}

function expectOk<T>(result: CallResult, statuses: number[], fallback: string): T {
  if (!statuses.includes(result.status)) {
    throw new ApiError(detailOf(result.json, fallback), result.status, result.retryAfter);
  }
  return result.json as T;
}

export async function getHealth(): Promise<boolean> {
  const result = await send("/actuator/health", { method: "GET", accept: "application/json" });
  if (result.status !== 200 || !result.json || typeof result.json !== "object") {
    return false;
  }
  return (result.json as { status?: unknown }).status === "UP";
}

let sessionPromise: Promise<Session> | null = null;

export function ensureSession(): Promise<Session> {
  if (!sessionPromise) {
    sessionPromise = openSession().catch((error: unknown) => {
      sessionPromise = null;
      throw error;
    });
  }
  return sessionPromise;
}

async function openSession(): Promise<Session> {
  if (mocksAllowed()) {
    return {
      tenantId: "dev",
      token: "dev-session",
      expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    };
  }
  const result = await send("/demo/session", { method: "POST" });
  if (result.status === 404) {
    throw new ApiError("The sandbox session is not available on this API yet.", 404, result.retryAfter);
  }
  const session = expectOk<Session>(result, [200, 201], "The sandbox session could not be opened.");
  if (!session || typeof session.token !== "string" || typeof session.tenantId !== "string") {
    throw new ApiError("The sandbox session response was not usable.", result.status, result.retryAfter);
  }
  return session;
}

export async function postTransaction(
  body: string,
  idempotencyKey: string,
  session: Session,
): Promise<CallResult> {
  return send("/transactions", { method: "POST", body, idempotencyKey, session });
}

export async function createTransaction(
  body: string,
  idempotencyKey: string,
  session: Session,
): Promise<TransactionCreated> {
  const result = await postTransaction(body, idempotencyKey, session);
  return expectOk(result, [201], "The charge was not recorded.");
}

export async function createDocument(
  body: string,
  idempotencyKey: string,
  session: Session,
): Promise<DocumentCreated> {
  const result = await send("/documents", { method: "POST", body, idempotencyKey, session });
  return expectOk(result, [201], "The receipt was not recorded.");
}

export async function reconcile(
  transactionId: string,
  idempotencyKey: string,
  session: Session,
): Promise<ReconcileResult> {
  const result = await send(`/reconcile/${transactionId}`, {
    method: "POST",
    idempotencyKey,
    session,
  });
  return expectOk(result, [200], "The charge could not be matched.");
}

export async function getMatch(matchId: string, session: Session): Promise<Match> {
  const result = await send(`/matches/${matchId}`, { method: "GET", session, accept: "application/json" });
  return expectOk(result, [200], "The match could not be read.");
}

export async function approveMatch(
  matchId: string,
  body: string,
  idempotencyKey: string,
  session: Session,
): Promise<Decision> {
  const result = await send(`/matches/${matchId}/approve`, {
    method: "POST",
    body,
    idempotencyKey,
    session,
  });
  return expectOk(result, [200], "The match was not approved.");
}

export async function approveMatchRaw(
  matchId: string,
  body: string,
  idempotencyKey: string,
  session: Session,
): Promise<CallResult> {
  return send(`/matches/${matchId}/approve`, {
    method: "POST",
    body,
    idempotencyKey,
    session,
  });
}

export async function getHistory(matchId: string, session: Session): Promise<HistoryEvent[]> {
  const result = await send(`/matches/${matchId}/history`, {
    method: "GET",
    session,
    accept: "application/json",
  });
  const events = expectOk<HistoryEvent[]>(result, [200], "The audit timeline could not be read.");
  if (!Array.isArray(events)) {
    throw new ApiError("The audit timeline was not a list.", result.status, result.retryAfter);
  }
  return events;
}

/**
 * Sandbox contract, not on main yet: GET /demo/export returns text/csv for the session.
 * A 404 is a dev stand-in only when mocks are allowed. Production surfaces the error.
 */
export async function exportApproved(
  session: Session,
  approved: {
    matchId: string;
    transactionId: string;
    documentId: string | null;
    status: string;
    score: number | string | null;
    version: number;
  },
): Promise<ExportResult> {
  const result = await send("/demo/export", { method: "GET", session, accept: "text/csv" });
  if (result.status === 200 && result.raw.trim()) {
    return { csv: result.raw, mocked: false, status: result.status, retryAfter: result.retryAfter };
  }
  if (result.status === 404 && mocksAllowed()) {
    const score = approved.score === null ? "" : String(approved.score);
    const documentId = approved.documentId ?? "";
    const csv = [
      "matchId,transactionId,documentId,status,score,version",
      [approved.matchId, approved.transactionId, documentId, approved.status, score, approved.version].join(","),
    ].join("\n");
    return { csv: `${csv}\n`, mocked: true, status: 200, retryAfter: null };
  }
  if (result.status === 404) {
    throw new ApiError("The export route is not available on this API yet.", 404, result.retryAfter);
  }
  throw new ApiError(detailOf(result.json, "The export could not be downloaded."), result.status, result.retryAfter);
}

export function problemDetail(json: unknown, fallback: string): string {
  return detailOf(json, fallback);
}
