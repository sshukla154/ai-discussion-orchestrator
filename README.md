# ai-discussion-orchestrator

A local tool that runs a structured argument between two AI models and keeps the state itself, so
the discussion does not lose its thread and a person stays the one who decides.

The point is **not** to work out which model is right. It is to make disagreement legible: what
does each side actually claim, where do they genuinely agree, what evidence supports each
position, what is still open, and what is left for a human to decide.

Doing this by hand means shuttling text between two browser tabs. Context is lost at every hop,
neither side sees the whole thread, and there is no record afterwards of what was agreed rather
than merely asserted.

## Running a round

A round is two commands, because the challenger is a person pasting into a separate chat. Between
them nothing is running, so a laptop can be closed mid-discussion.

Write the question as markdown:

```markdown
# Question
Should we shard by tenant or by region?

# Objective
Pick one and know what we are trading away.

# Constraints
- single-writer SQLite, one connection
- no cross-region transactions
```

Run the architect's opening turn:

```bash
mvn -B -q compile spring-boot:run -Dspring-boot.run.arguments="--start --question=question.md"
```

It prints the run directory and the path of a challenger prompt. Paste that prompt into a separate
chat, and save the JSON reply to a file. Then:

```bash
mvn -B -q compile spring-boot:run -Dspring-boot.run.arguments="--answer --run=<run-dir> --reply=reply.json"
```

The result is a markdown document in the run directory: where the two sides landed, what was
settled, what is still open with why it matters and what would settle it, and what you have to
decide. It never reports agreement that both sides did not independently state.

Which model argues, and how hard, is configured under `aido.discussion` and recorded with every
turn -- without that, comparing two saved discussions is meaningless.

## Status

**Early.** Phase 0 (provider feasibility) is complete and its findings are in
[docs/provider-feasibility.md](docs/provider-feasibility.md). What works today is the piece that
carried the most risk: driving the Claude Code CLI as a child process and getting typed,
schema-validated results back out of it, including across separate processes.

The discussion engine itself is not built yet. This was deliberate — the integration was proven
before anything was layered on top of assumptions about it.

## Requirements

- JDK 25 and Maven 3.9+
- [Claude Code](https://claude.com/claude-code), installed and logged in

No API key is needed. The CLI is driven using its existing login; see the feasibility document
for how that was verified.

## Try it

```bash
mvn -B verify
```

That runs the full hermetic suite. Nothing in it touches the network or the real CLI: a stub
process stands in, so the tests still exercise real process spawning, argv construction, separate
stdout and stderr pipes, and forced termination.

```bash
mvn -B -q compile spring-boot:run -Dspring-boot.run.arguments=--probe
```

That runs one real turn against the installed CLI under a JSON schema and prints the parsed
result with token usage. It costs a few cents.

If the CLI is not on `PATH`, point at it explicitly:

```bash
mvn -B -q compile spring-boot:run -Dspring-boot.run.jvmArguments=-Daido.claude.executable=/path/to/claude
```

## Tests

Two tiers, separated from the first commit.

| Command | Scope |
|---|---|
| `mvn verify` | Hermetic. No network, no real CLI. Runs in CI. |
| `mvn verify -Plive` | Spawns the real CLI. Local only, costs money. |

The split is not a convenience. The CLI is not installed on a CI runner, and authenticating it
there would mean putting a credential into repository secrets — which this project does not do,
and which CI actively checks for.

## What is deliberately not here

Design decisions worth knowing before reading the code:

- **No browser automation of ChatGPT.** It violates the Terms of Use on two independent counts.
  Challenger interaction is a person copying a generated prompt and pasting the reply back, which
  is within terms and means no credential exists anywhere in this project.
- **No Docker.** The application spawns a CLI that lives on the host and reads the host login. A
  container reaches neither.
- **No web interface yet.** A three-round discussion is minutes of wall-clock, so this is a job
  runner rather than a request-response application. Adding HTTP early would have invited a
  request thread to block on a five-minute subprocess.

Full reasoning, including several approaches that were investigated and ruled out, is in
[docs/rejected-approaches.md](docs/rejected-approaches.md).

## Handling of your data

The database holds complete transcripts of every discussion, and is the most sensitive artefact
here. It lives outside the repository and is never committed. The challenger path sends content
to a third party under consumer terms, which needs a deliberate decision rather than an
assumption — see [docs/data-handling.md](docs/data-handling.md).

## Licence

[MIT](LICENSE)
