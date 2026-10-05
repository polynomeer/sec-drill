import { createContext, useCallback, useContext, useState, type ReactNode } from "react";

// One polite live region for state transitions only (07: do not read every poll or stream event aloud).
const Context = createContext<(message: string) => void>(() => {});

export function AnnounceProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState("");
  const announce = useCallback((next: string) => setMessage((previous) => (previous === next ? `${next} ` : next)), []);
  return (
    <Context.Provider value={announce}>
      {children}
      <div className="sr-only" role="status" aria-live="polite" data-testid="announcer">
        {message}
      </div>
    </Context.Provider>
  );
}

export const useAnnounce = () => useContext(Context);
