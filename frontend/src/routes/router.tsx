import { AppShell } from "@/components/AppShell";
import { RunDetailPage } from "@/features/runs/RunDetailPage";
import { RunsPage } from "@/features/runs/RunsPage";
import { SystemEditorPage } from "@/features/systems/SystemEditorPage";
import { SystemsPage } from "@/features/systems/SystemsPage";
import {
  createRootRoute,
  createRoute,
  createRouter,
  Navigate,
} from "@tanstack/react-router";

const rootRoute = createRootRoute({ component: AppShell });

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: () => <Navigate to="/runs" />,
});

const systemsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/systems",
  component: SystemsPage,
});

const systemEditorRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/systems/$systemId",
  component: SystemEditorPage,
});

const runsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/runs",
  component: RunsPage,
});

const runDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/runs/$runId",
  component: RunDetailPage,
});

const routeTree = rootRoute.addChildren([
  indexRoute,
  systemsRoute,
  systemEditorRoute,
  runsRoute,
  runDetailRoute,
]);

export const router = createRouter({ routeTree, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
