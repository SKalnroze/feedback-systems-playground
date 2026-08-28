import "@/styles/index.css";
import "@xyflow/react/dist/style.css";

import { router } from "@/routes/router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Simulations move on their own, so cached data goes stale quickly by nature. Refetching on
      // focus is what makes returning to a tab show the current state rather than a snapshot from
      // whenever it was last looked at.
      staleTime: 1000,
      refetchOnWindowFocus: true,
      retry: 1,
    },
  },
});

const container = document.getElementById("root");
if (!container) {
  throw new Error("index.html is missing its #root element");
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
