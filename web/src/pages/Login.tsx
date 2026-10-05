import { useState } from "react";
import { api } from "../api/client";
import { ErrorNotice } from "../components/Messages";

export function Login({ onLoggedIn }: { onLoggedIn: () => void }) {
  const [error, setError] = useState<unknown>(null);
  return (
    <section aria-labelledby="login-title" className="panel">
      <h1 id="login-title">로그인</h1>
      <p>
        <a href="/oauth2/authorization/oidc">OIDC로 로그인</a>
      </p>
      <p>
        <button
          type="button"
          onClick={async () => {
            try {
              await api("POST", "/v1/auth/dev-login", {});
              onLoggedIn();
            } catch (failure) {
              setError(failure);
            }
          }}
        >
          개발용 로그인(local profile)
        </button>
      </p>
      <ErrorNotice error={error} />
    </section>
  );
}
