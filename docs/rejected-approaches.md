# Rejected approaches

Each entry was considered, investigated, and ruled out. They are recorded rather than left as
stubs or TODOs, because a stub is an invitation to redo the work while a rejection with a reason
is not. If one of these becomes viable, the entry says what would have to change.

---

## Browser automation of the ChatGPT web interface

**Rejected permanently.** The Terms of Use prohibit automated or programmatic extraction of
output from the service, and separately prohibit circumventing rate limits. Two independent
grounds, neither ambiguous.

Not implemented, not prototyped, not stubbed. Manual copy-and-paste by a person using the
interface as intended is fully within terms and is what the project does instead.

*Would revisit if:* nothing short of a licence change, which is not something to design around.

---

## `--remote-control`

**Rejected on two grounds.** It starts an *interactive* session for an external client to drive,
which is the inverse of this architecture — a parent process running the CLI non-interactively
and reading its stdout. `--print` and remote control are opposite modes.

It is also disabled by organisation policy on the target machine, so it would not be available
even if it were the right shape.

*Would revisit if:* never, for this design. Session continuity is already solved by
`--session-id` and `--resume`.

---

## `--bare`

**Rejected.** It refuses to read the existing login and reports "Not logged in", which would
force creating and storing an Anthropic API key. Its leanness is appealing, but `--safe-mode`
delivers most of the same cold-start saving while keeping authentication working. Using `--bare`
would convert this into a credential-handling project for no benefit.

---

## ChatGPT quota and reset-window tracking

**Rejected as designed.** The original specification called for an eight-value provider status
enum, a five-value information-provenance enum, observed-limit tracking, and a
reset-window-verification state machine.

Three problems. The Free tier removed text limits on 2026-08-06, so it models a constraint that
largely no longer exists. Investigation found only two distinguishable CLI error shapes, and
eight statuses cannot be populated from two signals — most of the enum would be unreachable, and
the compiler cannot tell you which arms are dead. And with a manual provider, a person is the
only sensor, so the only exit from "reset window passed" is a human assertion, which is exactly
the unverified claim the design forbids the application from making.

**Kept instead:** an append-only provider-event log. That is the actual audit trail, it honours
"never fabricate a limit", and classification happens at the call site with three outcomes:
proceed, retry, blocked.

---

## Docker and Docker Compose

**Rejected for this application.** The core job is spawning a CLI that lives on the host and
reads the host login. A container reaches neither.

The workarounds are worse than the problem: mounting the credential directory into a container
puts a credential in a bind mount, violating the no-credentials rule; and having a container
call out to a host agent invents a network boundary to solve a problem that does not exist on a
single-user local tool.

*Would revisit if:* the Claude provider were replaced by a direct HTTP API client, at which point
there is no host binary to reach and containerising becomes coherent.

---

## Google Gemini as the challenger

**Rejected.** Attractive on paper: it is the most genuinely independent challenger available,
since it shares no post-training lineage with either Claude or the OpenAI models.

Two blockers on the free tier. Google marks free-tier content as used to improve their products,
and the payload here is private reasoning. Separately, the API terms restrict users in the EEA,
Switzerland and the UK to paid services, which is a live licence question for a developer
resident in the Netherlands rather than a theoretical one.

*Would revisit if:* billing were enabled, which resolves both objections and costs very little at
this volume. Worth reconsidering if challenge quality turns out to matter more than simplicity.

---

## GitHub Models

**Rejected: the service no longer exists.** Fully retired on 2026-07-30 — playground, catalogue
and inference API. Appealing beforehand because it would have reused an existing GitHub login,
but that is now moot. It also required a token scope that was not present.

---

## A community JVM SDK for the Claude Code CLI

**Rejected.** The official SDKs are Python and TypeScript only. The one JVM implementation found
carries no licence file, which means all rights reserved and no right to use it, and it was
thirteen months stale.

The subprocess client is written by hand instead. It is a couple of hundred lines, and owning it
means owning the two error classes and the session rules directly rather than discovering them
through someone else's abstraction.

---

## A JSON Schema passed to the CLI as a file path

**Rejected: not supported.** The CLI rejects a path with `--json-schema is not valid JSON`. It
accepts inline JSON only.

This was worth trying because inline JSON does not survive argv on Windows unescaped. Since the
file route is closed, the schema is pre-escaped so the child reconstructs it intact; see
`WindowsArgv`.

---

## An extra model call to compress working memory

**Rejected.** The original design required working memory to be updated after every response but
never said what performed the compression. An extra call per response would double the calls per
round, add minutes of wall-clock, and ask a debater to summarise its own argument inside a system
built to resist false consensus.

**Kept instead:** every turn returns prose *and* typed fields in a single call, using the schema
support verified in Phase 0. Working memory is assembled from those fields by ordinary code. No
extra calls, and no self-interested summariser.

---

## GitHub Pages for the interface

**Rejected.** Pages serves static files from a public URL with no server side. This application
needs a local backend and a local database. A Pages-hosted interface could only reach a backend
by having a public HTTPS page call the visitor's own machine, which is a mixed-content violation,
a cross-origin problem, and the precise opposite of an application that is never publicly
exposed.
