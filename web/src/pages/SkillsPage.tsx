import { useEffect, useState } from "react";
import { api, type SkillPage } from "../api/client";
import { CONFIDENCE, LEVELS } from "../components/Labels";
import { ErrorNotice } from "../components/Messages";

/** Skill profile (10): the level and the confidence are separate columns; UNKNOWN means not enough evidence, not low skill. */
export function SkillsPage() {
  const [page, setPage] = useState<SkillPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    api<SkillPage>("GET", "/v1/skills/me").then(setPage, setError);
  }, []);
  return (
    <section className="panel" aria-labelledby="skills-title">
      <h1 id="skills-title">스킬 프로파일</h1>
      <ErrorNotice error={error} />
      {page && (
        <>
          <p className="note">
            정책 {page.policyVersion} · taxonomy {page.taxonomyVersion}. 시스템 오류·오답 플래그·데모 결과는 표본에서 빠지고, 같은 계열 반복과 연결된 Session은 한 번만 셉니다. 성공률은
            숙련 확률이 아닙니다.
          </p>
          <div className="table-wrap" tabIndex={0} aria-label="스킬 표">
            <table>
              <thead>
                <tr>
                  <th scope="col">역량</th>
                  <th scope="col">수준</th>
                  <th scope="col">신뢰도</th>
                  <th scope="col">표본</th>
                  <th scope="col">계열</th>
                  <th scope="col">가중 성공률</th>
                  <th scope="col">근거</th>
                </tr>
              </thead>
              <tbody>
                {page.skills.map((skill) => (
                  <tr key={skill.key} data-skill={skill.key}>
                    <th scope="row">{skill.key}</th>
                    <td data-cell="level">{LEVELS[skill.level] ?? skill.level}</td>
                    <td data-cell="confidence">{CONFIDENCE[skill.confidence] ?? skill.confidence}</td>
                    <td>{skill.sampleCount}</td>
                    <td>{skill.familyCount}</td>
                    <td>{skill.successBps == null ? "–" : `${Math.round(skill.successBps / 100)}%`}</td>
                    <td>{skill.evidenceIds.length}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}
