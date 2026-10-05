// Fails when the committed API types differ from types freshly generated from the OpenAPI contract.
import { readFileSync, rmSync } from "node:fs";
const [committed, fresh] = process.argv.slice(2);
const same = readFileSync(committed, "utf8") === readFileSync(fresh, "utf8");
rmSync(fresh);
if (!same) {
  console.error(`${committed} is out of date with the OpenAPI contract; run npm run gen:api`);
  process.exit(1);
}
