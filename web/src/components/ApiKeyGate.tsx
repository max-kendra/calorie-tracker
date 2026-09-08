import { useState, type ReactNode } from "react";
import { getStoredApiKey, setStoredApiKey, UnauthorizedError } from "@/api/client";
import { useQueryClient } from "@tanstack/react-query";

/** Blocks rendering `children` until an API key is stored. Doesn't yet
 * detect a WRONG key automatically (that would need catching
 * UnauthorizedError from wherever queries actually fail and calling
 * clearStoredApiKey() + forcing a re-render here) - for now, if the
 * key turns out to be wrong, every query will show its own 401 error
 * and you'd clear localStorage manually to re-trigger this gate. Fine
 * for a single-user personal tool; worth revisiting if this becomes
 * annoying in practice. */
export function ApiKeyGate({ children }: { children: ReactNode }) {
  const [hasKey, setHasKey] = useState(() => getStoredApiKey() !== null);
  const [input, setInput] = useState("");
  const queryClient = useQueryClient();

  function submit() {
    if (!input.trim()) return;
    setStoredApiKey(input.trim());
    setHasKey(true);
    queryClient.invalidateQueries();
  }

  if (!hasKey) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-100">
        <div className="bg-white rounded-2xl shadow-sm p-6 w-full max-w-sm">
          <h1 className="text-lg font-semibold text-gray-800 mb-1">Meal Tracker</h1>
          <p className="text-sm text-gray-500 mb-4">Enter your API key to continue.</p>
          <input
            type="password"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && submit()}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm mb-3"
            placeholder="API key"
            autoFocus
          />
          <button
            onClick={submit}
            className="w-full bg-blue-500 text-white rounded-lg py-2 text-sm font-medium hover:bg-blue-600"
          >
            Continue
          </button>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}

// Re-exported so callers elsewhere can check `error instanceof
// UnauthorizedError` without importing straight from api/client.
export { UnauthorizedError };
