import { defineConfig } from "vitest/config";

/**
 * Two suites, deliberately separated:
 *
 *   unit        — pure domain logic, no I/O. Runs in milliseconds, no Postgres.
 *   integration — the transactional shell. Needs a real Postgres, because the
 *                 guarantees under test (SERIALIZABLE conflict detection,
 *                 append-only triggers) are the database's, not ours. An
 *                 in-memory fake would test nothing that matters.
 *
 * `pnpm test` runs unit only, so the fast loop stays fast.
 * `pnpm test:integration` runs the rest.
 */
export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: "unit",
          include: ["src/**/*.test.ts"],
          exclude: ["src/**/*.integration.test.ts"],
          environment: "node",
        },
      },
      {
        test: {
          name: "integration",
          include: ["src/**/*.integration.test.ts"],
          environment: "node",
          // Integration tests share one database; let them coordinate rather
          // than race each other's fixtures.
          fileParallelism: false,
          testTimeout: 30_000,
          hookTimeout: 30_000,
        },
      },
    ],
    coverage: {
      provider: "v8",
      include: ["src/domain/**/*.ts", "src/server/**/*.ts"],
      exclude: ["**/*.test.ts", "**/*.integration.test.ts"],
    },
  },
});
