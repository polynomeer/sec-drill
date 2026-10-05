// Learner-visible output is never HTML (07). React escapes markup; this also removes ANSI escape sequences and
// control characters so terminal colours, cursor moves or bidi tricks from Lab output cannot reach the page.
const ANSI = /\u001b\[[0-9;?]*[ -/]*[@-~]|\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)|\u001b[@-Z\\-_]/g;
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f‪-‮⁦-⁩]/g;

export function safeText(value: unknown, max = 2000): string {
  const text = typeof value === "string" ? value : value === null || value === undefined ? "" : JSON.stringify(value);
  const clean = text.replace(ANSI, "").replace(CONTROL, "");
  return clean.length > max ? `${clean.slice(0, max)}…` : clean;
}
