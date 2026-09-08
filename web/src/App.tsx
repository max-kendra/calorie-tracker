import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { ApiKeyGate } from "@/components/ApiKeyGate";
import { WeekView } from "@/pages/WeekView";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApiKeyGate>
        <div className="min-h-screen bg-gray-100">
          <BrowserRouter>
            <Routes>
              <Route path="/" element={<WeekView />} />
              {/* Item creation (USDA search / barcode number / barcode
                  image upload) and day-level editing land here next -
                  routed separately so the week view stays the default
                  landing page. */}
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </BrowserRouter>
        </div>
      </ApiKeyGate>
    </QueryClientProvider>
  );
}
