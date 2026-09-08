const API_KEY_STORAGE_KEY = "meal-tracker-api-key";

/** Same shared-secret model as the Android client (see app/auth.py's
 * own docstring: "simple shared-secret auth, appropriate for a
 * single-user personal app reachable only over Tailscale") - there's
 * no per-user account system to build a real login against, so this is
 * just "enter the key once, keep it in localStorage, attach it to
 * every request" rather than a token/session flow. */
export function getStoredApiKey(): string | null {
  return localStorage.getItem(API_KEY_STORAGE_KEY);
}

export function setStoredApiKey(key: string): void {
  localStorage.setItem(API_KEY_STORAGE_KEY, key);
}

export function clearStoredApiKey(): void {
  localStorage.removeItem(API_KEY_STORAGE_KEY);
}

/** Thrown specifically on a 401 so ApiKeyGate can catch it and re-
 * prompt (e.g. the stored key was wrong, or got rotated server-side)
 * without every call site needing its own 401 handling. */
export class UnauthorizedError extends Error {
  constructor() {
    super("Invalid or missing API key");
    this.name = "UnauthorizedError";
  }
}

/** In dev, Vite's proxy (see vite.config.ts) forwards /api/* to the
 * real backend, so the browser never makes a cross-origin request. In
 * production, the built static files are served BY FastAPI itself
 * (see README) at the same origin as the API, so a plain relative
 * "/..." path works there too without needing this at all - set
 * VITE_API_BASE_URL only if you're deploying the built frontend
 * somewhere separate from the API. */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "/api";

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const apiKey = getStoredApiKey();
  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      ...(init.body ? { "Content-Type": "application/json" } : {}),
      "X-API-Key": apiKey ?? "",
      ...init.headers,
    },
  });

  if (response.status === 401) {
    throw new UnauthorizedError();
  }
  if (!response.ok) {
    const detail = await response.text().catch(() => "");
    throw new Error(`${response.status} ${response.statusText}${detail ? `: ${detail}` : ""}`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body !== undefined ? JSON.stringify(body) : undefined }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "PATCH", body: body !== undefined ? JSON.stringify(body) : undefined }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  /** For multipart uploads (barcode image scan, product photo, label
   * OCR) - deliberately doesn't set Content-Type, letting the browser
   * set the multipart boundary itself. */
  postForm: <T>(path: string, form: FormData) => {
    const apiKey = getStoredApiKey();
    return fetch(`${BASE_URL}${path}`, {
      method: "POST",
      body: form,
      headers: { "X-API-Key": apiKey ?? "" },
    }).then(async (response) => {
      if (response.status === 401) throw new UnauthorizedError();
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
      return response.json() as Promise<T>;
    });
  },
};
