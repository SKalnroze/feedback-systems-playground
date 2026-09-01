import { AppShell } from "@/components/AppShell";
import { RunDetailPage } from "@/features/runs/RunDetailPage";
import { ComparePage } from "@/features/runs/ComparePage";
import { ObjectsPage } from "@/features/objects/ObjectsPage";
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

const objectsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/objects",
  component: ObjectsPage,
});

const compareRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/compare",
  component: ComparePage,
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
  compareRoute,
  objectsRoute,
]);

export const router = createRouter({ routeTree, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
