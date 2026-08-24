# Provider feasibility (Phase 0)

Status: **cleared**. Measured 2026-08-24 on the target machine (Windows 11, Claude Code CLI 2.1.241).

This document is the gate the project design placed before any application code: can the two
providers actually be driven the way the architecture assumes? The answer changed several
design decisions, so the evidence is recorded rather than summarised away.

Findings are stated generically. Install locations, account identifiers and live session ids
are machine-specific and are deliberately absent — see the redaction check in
`scripts/redaction-check.sh`.

---

## Claude: feasible, and no API key is required

| Question | Answer |
|---|---|
| Can a locally spawned CLI reuse an existing Claude Team login? | **Yes** |
| Is a separate Anthropic API key needed? | **No** |
| Does non-interactive mode return structured output? | Yes, via `--output-format json` |
| Can a session be resumed by a different process? | **Yes** |
| Is resume tied to the launch directory? | No — it is a global lookup by session id |
| Is schema-constrained output enforced? | Yes |

**Auth.** A child process inherits the existing login from disk. This was verified with every
`CLAUDE_*` and `ANTHROPIC_*` environment variable removed, so the result is not an artefact of
a parent process passing credentials down. `claude auth status --json` gives a machine-readable,
secret-free health check and is the right pre-flight probe.

`--bare` must never be used: it refuses to read the existing login and reports "Not logged in",
which would force creating an API key. That is the one flag capable of turning this project into
a credential-handling project.

**Session continuity.** A word stored in turn one was recalled by a second, independent process
resuming the same session id. This is the single result the whole architecture rests on. Two
constraints come with it:

- A session id may be passed to `--session-id` exactly once. Every later turn must use
  `--resume`. A retry that reuses the id fails with "already in use", so **retries must mint a
  fresh id** rather than reusing one.
- `--fork-session` preserves context but returns a *different* session id, which must be
  captured from the response rather than assumed.

**Structured output.** `--json-schema` is genuinely enforced: given an enum-constrained schema
and a prompt asking for a long essay, the reply came back as the enum value with no extra keys
and the essay discarded. Two consequences for the code: the parsed object arrives in
`structured_output`, and `stop_reason` becomes `tool_use` rather than `end_turn`, so success
must not be asserted on `end_turn`.

The schema can only be supplied **inline**. A file path is rejected with
`--json-schema is not valid JSON`. This matters more than it sounds; see the argv note below.

**Remote control** is not applicable, on two independent grounds. It starts an *interactive*
session for an external client to drive, which is the opposite of the non-interactive,
parent-reads-stdout model used here. It is also disabled by organisation policy on the target
machine. Excluded from the design.

### Four behaviours the code has to account for

1. **`subtype` is not a success signal.** It reads `"success"` even when `is_error` is `true`.
   Branch on the exit code, then on `is_error`.
2. **There are two error classes.** API and model errors return a parseable envelope on stdout
   with `is_error: true`, plus a diagnostic line on stderr. Pre-flight argument validation
   failures return **no JSON at all**: empty stdout, plain text on stderr, and
   `--output-format json` silently ignored. A parser that assumes stdout is always JSON breaks
   on every one of these. Observed instances: unknown session, malformed session id, session id
   reused for creation, and a `--json-schema` value that is not inline JSON.
3. **`modelUsage` is not single-entry.** A lightweight auxiliary model appears alongside the one
   that served the turn, for side work such as session-title generation. There is no top-level
   `model` field, so the primary must be identified by matching the top-level `usage` token
   counts. Reading "the only key" returns the wrong model.
4. **Inline JSON does not survive argv on Windows.** Measured: a single argument
   `{"type":"object","p":"C:\tmp 100%"}` arrived at the child as **two** arguments with every
   double quote stripped — consumed as grouping characters, and the value then split on the
   space those quotes should have protected. Minifying does not help. The fix is to pre-escape
   each quote so the child reconstructs the original text; see `WindowsArgv`. Verified against
   the real CLI, not just a stub. The prompt avoids this entirely by travelling through stdin.

### Cost and latency

Roughly 10 seconds of process startup per invocation, on top of the model work itself. Design
timeouts accordingly: a substantive turn wants 180–300s, and nothing should be under 30s.

Configuration loading dominates cold-start cost. Measured, for the same trivial turn:

| Configuration | Cache-creation tokens |
|---|---|
| Default settings, inside a project directory | 56,000–68,000 |
| `--safe-mode --strict-mcp-config --tools ""`, empty directory | **~5,500** |

Roughly an order of magnitude, for a prompt whose real content was two tokens — the difference
is project configuration, skills, plugins and MCP definitions being loaded on every launch.
Subsequent invocations fall further still, to ~1,700 created against ~3,300 read from cache,
because the lean prefix caches across processes.

This is why every invocation is hardened: disabling tools is primarily an injection control (one
model output becomes another model input, inside a CLI that can otherwise run commands), and the
cost saving is a second, independent reason to do it.

---

## ChatGPT: manual only, and the earlier assumptions were stale

**A ChatGPT Free subscription grants no API access.** The consumer product and the developer
platform are separate, with separate accounts and separate prepaid billing. No amount of
consumer subscription converts into programmatic access.

**Automating the web interface is not an option.** The Terms of Use prohibit automated or
programmatic extraction of output from the service, and separately prohibit circumventing rate
limits. This is settled rather than pending investigation, so browser automation is excluded
outright — not deferred, and not left as a stub. A human using the interface as intended is
entirely within terms, which is what this project does.

**Text limits were removed for the Free tier on 2026-08-06**, alongside a model upgrade and the
addition of a higher-reasoning mode. Caps remain on file uploads, images and voice. This
substantially undercuts the elaborate quota-tracking subsystem the original design specified for
ChatGPT: it modelled a constraint that largely no longer applies to text.

**Limits were never machine-readable** in any case — an interface banner in prose, on a
per-user rolling window. Moot here, since nothing automated reads it.

**The scarce resource is Claude quota, not ChatGPT quota.** The original design tracked usage for
the provider that just removed its limits and not for the one that will actually be exhausted.
Recording the per-turn token usage the CLI already reports is the useful version of that feature.

---

## Consequences for the design

- Challenger interaction is **manual**: the application renders a prompt to paste, and ingests
  the reply. No credential exists anywhere in the project as a result.
- A synchronous `generate()` call cannot coexist with operations that survive a restart, and a
  human-latency provider makes that contradiction acute. The provider interface is submit/poll
  over a persisted ticket, and **no database transaction may span a provider call**.
- Working memory is assembled from typed fields returned by each turn, using the schema support
  proven above. No separate summarisation call, so a round costs what a round costs.
- Every invocation runs with tools disabled, no MCP, and no project configuration discovery.
