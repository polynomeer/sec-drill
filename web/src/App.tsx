import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "./api/client";
import { AnnounceProvider } from "./announce";
import { ErrorNotice } from "./components/Messages";
import { Catalog } from "./pages/Catalog";
import { Login } from "./pages/Login";
import { ReplayPage } from "./pages/ReplayPage";
import { ReportPage } from "./pages/ReportPage";
import { SkillsPage } from "./pages/SkillsPage";
import { ScenarioPage } from "./pages/ScenarioPage";
import { Workspace } from "./pages/Workspace";
import { href, navigate, useLocation } from "./router";

type Auth = "checking" | "in" | "out" | { error: unknown };

export function App() {
  const { path } = useLocation();
  const [auth, setAuth] = useState<Auth>("checking");
  const check = useCallback(() => {
    api("GET", "/v1/auth/session").then(
      () => setAuth("in"),
      (error) => setAuth(error instanceof ApiError && error.status === 401 ? "out" : { error }),
    );
  }, []);
  useEffect(check, [check]);

  let page;
  if (auth === "checking") page = <p>확인 중…</p>;
  else if (auth === "out") page = <Login onLoggedIn={check} />;
  else if (typeof auth === "object") page = <ErrorNotice error={auth.error} onRetry={check} />;
  else {
    const scenario = path.match(/^\/scenarios\/([0-9a-f-]{36})$/);
    const session = path.match(/^\/sessions\/([0-9a-f-]{36})$/);
    const sub = path.match(/^\/sessions\/([0-9a-f-]{36})\/(report|replay)$/);
    if (scenario) page = <ScenarioPage scenarioId={scenario[1]} />;
    else if (sub && sub[2] === "report") page = <ReportPage key={sub[1]} sessionId={sub[1]} />;
    else if (sub && sub[2] === "replay") page = <ReplayPage key={sub[1]} sessionId={sub[1]} />;
    else if (path === "/skills") page = <SkillsPage />;
    else if (session) page = <Workspace key={session[1]} sessionId={session[1]} />;
    else page = <Catalog />;
  }

  return (
    <AnnounceProvider>
      <a className="skip-link" href="#content">
        본문으로 건너뛰기
      </a>
      <header className="site-header">
        <a
          href={href("/scenarios")}
          onClick={(event) => {
            event.preventDefault();
            navigate("/scenarios");
          }}
        >
          SecDrill
        </a>{" "}
        <a
          href={href("/skills")}
          onClick={(event) => {
            event.preventDefault();
            navigate("/skills");
          }}
        >
          스킬
        </a>
      </header>
      <div id="content" tabIndex={-1}>
        {page}
      </div>
    </AnnounceProvider>
  );
}
