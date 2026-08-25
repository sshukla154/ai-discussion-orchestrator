# ai-discussion-orchestrator

Local-only tool that runs a structured argument between two AI models, holds the discussion state
itself, and leaves the decision to a person. Personal project, public repository.

Read [docs/provider-feasibility.md](docs/provider-feasibility.md) before changing anything in
`provider/claude` — it records measured CLI behaviour that is not guessable from the docs, and
several of the surprises there are load-bearing.

**Before adding a round, a convergence check, a database or a UI, read
[docs/value-experiment.md](docs/value-experiment.md) and
[docs/rejected-approaches.md](docs/rejected-approaches.md).** Each was dropped on measured
evidence rather than left undone, and each rejection records the condition that would reverse
it. One round is the whole design, not a configured limit.

## Stack

| | |
|---|---|
| Java | 25 (only `<java.version>` is set; the Boot parent derives `maven.compiler.release`) |
| Spring Boot | 4.1.1 — **not** a web application, and not becoming one |
| Jackson | **3.x**, so imports are `tools.jackson.*`, not `com.fasterxml.jackson.*` (annotations are still `com.fasterxml.jackson.annotation`) |
| Tests | JUnit Jupiter 6, AssertJ. No mocking of the CLI boundary — a real stub process is spawned instead, see `StubCli` |
| Build | Maven 3.9+ |
| Persistence | **test scope only, permanently.** Runs write plain files; `ArchitectureTest` fails the build if main sources touch `jakarta.persistence`, `org.hibernate`, `org.springframework.data.jpa` or `org.flywaydb`. The database that was to make it compile scope is dropped — do not promote it |

## Commands

```bash
mvn -B verify                 # hermetic suite; runs in CI
mvn -B verify -Plive          # spawns the real CLI; local only, costs money
scripts/redaction-check.sh    # what CI enforces about public content
```

A `@SpringBootTest` needs `@ActiveProfiles("persistence")` if it wants a datasource — the JPA
starter is on the test classpath, so autoconfiguration will otherwise fail for want of a URL.
`src/test/resources/application-persistence.yaml` holds that configuration.

## Rules that are not style preferences

These are enforced by `ArchitectureTest`, not by reviewer memory: no class that can reach
`ClaudeCliClient` is `@Transactional`, `Propagation.REQUIRES_NEW` appears nowhere, and main
sources carry no persistence dependency. Fixtures under `archfixture` prove each rule actually
rejects a violator, because an architecture rule that matches nothing is green and worthless.

**No database transaction may span a provider call.** A turn can legitimately run for five
minutes, and the connection pool is size 1 because SQLite has a single writer. Holding a
transaction across a subprocess call blocks every other query, so the application appears dead.
Orchestration classes are therefore not `@Transactional`; they call small transactional
collaborators. `Propagation.REQUIRES_NEW` is banned outright — with one connection it deadlocks
against itself.

**Never branch on `subtype`.** The CLI reports `subtype: "success"` alongside `is_error: true`.
Branch on the exit code, then `is_error`.

**Handle both CLI error classes.** API errors return JSON on stdout. Pre-flight argument
failures return no JSON at all — empty stdout, plain stderr, exit 1, `--output-format json`
ignored. Any parser assuming stdout is JSON breaks on the second class.

**Drain stdout and stderr concurrently.** The streams cannot be merged, because which stream
carried the payload is the signal that distinguishes the two error classes. Once separated, a
child that fills a pipe nobody is reading blocks forever. The symptom is "works on short prompts,
hangs on real ones".

**The prompt goes on stdin, never argv.** Inline JSON does not survive argv on Windows: quotes
are consumed as grouping characters and the value splits on whitespace. The schema has no such
escape hatch — the CLI rejects a file path — so it is pre-escaped by `WindowsArgv`.

**Never use `--bare`.** It refuses the existing login and would force creating an API key.

**Session ids are created once.** `--session-id` works exactly once per id; every later turn uses
`--resume`. A retry mints a **fresh** id rather than reusing one, which fails with "already in
use".

**Spawn with tools disabled and no project configuration.** One model output becomes another
model input inside a CLI that can otherwise run commands. This is an injection control first; the
order-of-magnitude cold-start cost saving is a bonus.

**Control decisions come only from typed schema fields.** Convergence, stop and decision values
are never parsed out of prose, so a model cannot end a discussion by writing a persuasive
sentence.

## The one credential

The challenger can run over Groq's API instead of a person pasting into a chat. Its key is read
from the `GROQ_API_KEY` **environment variable and nowhere else** — there is deliberately no
property, no file and no command-line flag for it, because the first two get committed and the
third lands in shell history and the process list.

With no key set the challenger falls back to the manual path. That is a designed default, not a
failure: `--start` writes the prompt for a person either way.

`gitleaks` and `scripts/redaction-check.sh` both match the key shape, and the redaction check also
rejects a hardcoded `GROQ_API_KEY=` assignment. Both are verified against planted keys, because a
scanner that has never rejected anything is not known to work.

## Public repository, so

Never commit: absolute paths under a user home, email addresses, account or organisation
identifiers, real session ids, or anything under `.claude/`. Derive paths at runtime — see
`ClaudeCliLocator`. `scripts/redaction-check.sh` enforces this in CI and covers the leak class
secret scanners ignore.

**No workflow may require a repository secret.** That is the credential rule expressed as a
checkable property rather than a paragraph, and CI fails if it is violated. `GITHUB_TOKEN` is
minted per run and does not count.

## Commits

`<type>: <description>` — imperative, sentence case, no trailing period. Types: `feat`, `fix`,
`refactor`, `docs`, `test`, `chore`, `perf`, `ci`.

**One line. No body.** Reasoning that needs more than a subject line belongs in `docs/`, where it
stays discoverable, rather than in a commit message nobody greps.

Keep commits small and incremental. Move documentation with the code rather than in a later sweep.
