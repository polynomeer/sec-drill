import { useEffect, useState } from "react";
import { api, type ScenarioPage } from "../api/client";
import { ErrorNotice } from "../components/Messages";
import { href, navigate, useLocation } from "../router";
import { safeText } from "../text";

const MODES = ["CTF", "WARGAME", "PURPLE"] as const;
const DIFFICULTIES = ["BEGINNER", "INTERMEDIATE", "ADVANCED"] as const;

export function Catalog() {
  const { query } = useLocation();
  const mode = query.get("mode") ?? "";
  const difficulty = query.get("difficulty") ?? "";
  const [page, setPage] = useState<ScenarioPage | null>(null);
  const [error, setError] = useState<unknown>(null);

  function setFilter(name: string, value: string) {
    const next = new URLSearchParams(query);
    if (value) next.set(name, value);
    else next.delete(name);
    const search = next.toString();
    navigate(`/scenarios${search ? `?${search}` : ""}`);
  }

  useEffect(() => {
    const params = new URLSearchParams();
    if (mode) params.set("mode", mode);
    if (difficulty) params.set("difficulty", difficulty);
    setError(null);
    api<ScenarioPage>("GET", `/v1/scenarios${params.size ? `?${params}` : ""}`).then(setPage, setError);
  }, [mode, difficulty]);

  return (
    <section aria-labelledby="catalog-title" className="panel">
      <h1 id="catalog-title">사건 카탈로그</h1>
      <div className="filters">
        <label>
          모드
          <select value={mode} onChange={(event) => setFilter("mode", event.target.value)}>
            <option value="">전체</option>
            {MODES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <label>
          난이도
          <select value={difficulty} onChange={(event) => setFilter("difficulty", event.target.value)}>
            <option value="">전체</option>
            {DIFFICULTIES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorNotice error={error} />
      {page && page.items.length === 0 && <p>조건에 맞는 출판된 사건이 없습니다.</p>}
      <ul className="cards">
        {page?.items.map((item) => (
          <li key={item.scenarioVersionId}>
            <a
              href={href(`/scenarios/${item.id}`)}
              onClick={(event) => {
                event.preventDefault();
                navigate(`/scenarios/${item.id}`);
              }}
            >
              <strong>{safeText(item.title, 200)}</strong>
            </a>
            <span>
              {item.difficulty} · {item.estimatedMinutes}분 · {item.modes.join(", ")}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
