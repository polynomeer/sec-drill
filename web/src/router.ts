import { useEffect, useState } from "react";

// Tiny history router for the /app/ base: no dependency, URL state is the source of truth (07: filters in the URL).
const BASE = "/app";

export function currentPath(): string {
  const path = window.location.pathname.startsWith(BASE) ? window.location.pathname.slice(BASE.length) : window.location.pathname;
  return path === "" ? "/" : path;
}

export function navigate(to: string): void {
  window.history.pushState(null, "", BASE + to);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

export function useLocation(): { path: string; query: URLSearchParams } {
  const [, setTick] = useState(0);
  useEffect(() => {
    const update = () => setTick((n) => n + 1);
    window.addEventListener("popstate", update);
    return () => window.removeEventListener("popstate", update);
  }, []);
  return { path: currentPath(), query: new URLSearchParams(window.location.search) };
}

export function href(to: string): string {
  return BASE + to;
}
