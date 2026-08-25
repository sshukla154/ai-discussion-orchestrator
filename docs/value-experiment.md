# Does the debate earn its cost?

The plan made this a gate rather than a nice-to-have: everything after phase one — the convergence
subsystem, the round loop, the pause and resume machinery — is only worth building if a structured
debate produces materially better decisions than a single well-prompted call. Feasibility was
proven early. Value was not, and could not be argued into existence.

**Verdict: keep one round. Do not build the convergence subsystem.** The reasoning is below,
including the parts that argue the other way.

## Method

Three questions, each a decision this project had already made, so ground truth exists. Both arms
answered by the same model family under the same hardening, and the architect ran as a fresh
session with no project configuration, so it had never seen the answer it was being asked about.

| Arm | Shape |
|---|---|
| **Debate** | architect states a position, an independent model attacks it |
| **Control** | one call: answer, then argue the strongest case against your own answer, then revise |

The control is the honest baseline. A self-critique call is cheap, needs no second provider, and is
what the debate has to beat. Its weakness is structural: a model steelmanning itself shares its own
priors by construction.

## Result

| Question | Novel versus the control? | Correct? |
|---|---|---|
| Dependency scope | yes — disable the autoconfigurations rather than moving Maven scope | **no.** Its blocking claim was that test scope breaks application startup. This repository does exactly that and starts fine: Boot autoconfiguration is conditional on classpath presence, so absent classes simply do not activate |
| Provider interface | **yes — atomicity between provider completion and the database write** | yes, and it found a real gap |
| Repository visibility | no | worse. It claimed a public leak is reversible through history rewriting; the control correctly called such exposure unrevocable |

One genuine insight in three, alongside two confident errors that a reader without independent
knowledge would have been misled by.

## The finding that paid for the experiment

On the provider interface the challenger conceded the synchronous design was broken — twice,
explicitly — and then attacked the replacement:

> The transition from provider completion to writing COMPLETED must be atomic.

The design answers that with a reconciliation oracle. After a crash, read the CLI's own transcript
and match on a hash of the prompt to learn whether the turn actually completed.

**The API-backed challenger has no such oracle.** There is no transcript to read and no endpoint to
ask "did you answer this prompt?". So a crash between the response arriving and the result being
recorded is genuinely unknowable for that provider, and the recovery design had quietly assumed the
subprocess case applied everywhere.

That gap is real, it was unaddressed, and neither this project nor the control arm found it.

## Measured constraints

| | |
|---|---|
| Architect turn | 7,036–10,778 output tokens, 91–123s, about $0.08–$0.14 |
| Challenger turn | 2,655–3,182 output tokens, 6.6–7.5s, free tier |
| Output ratio | challenger runs at roughly a quarter of the control's output budget |
| Per-minute allowance | one round consumed the entire window; one question reported zero tokens remaining |

Two things follow. A round is dominated by the architect, not by the debate — so the debate is cheap
in wall-clock terms once a position exists. And the free tier supports one round per minute, not
three, which points at the same conclusion the quality data does.

Total for both arms across three questions: roughly $1.60.

## What this decides

**`maxRounds` is 1.** A second round would spend money having the architect rebut claims that a
human can falsify in seconds, as happened twice here.

**No convergence subsystem.** Its purpose was to decide automatically when a debate had converged.
At one round there is nothing to converge, and the evidence says the useful output is the
disagreement itself rather than a verdict about it.

This is not a disappointing outcome. It is the outcome the experiment existed to make possible, and
it deletes more work than it creates. It also confirms the design's own framing: the value is in
making disagreement explicit for a person to adjudicate, not in resolving it.

**Kept:** the single round, typed turns, the run record, the claim ledger, and both providers.

**Dropped:** multi-round orchestration, automatic convergence detection, and the pause-and-resume
machinery that only a long debate would need.

**Closed since:** the atomicity gap above, though not as framed. Groq offers no idempotency key and there is no resume path for an unknown outcome to matter to, so the fix was narrower than the objection suggested. Details in the rejected-approaches record.

## Addendum: what the tool actually produces

Written after the experiment, once the tool could finish a round. It reframes the verdict rather
than contradicting it, and it is the most useful thing learned here.

The scoring above counted insights per question, because that is what a comparison against a
self-critique call measures. The first complete artifact showed that this is not the shape of the
output at all. Asked whether to move persistence dependencies to test scope, the architect did not
produce a better answer than the control. It produced a **narrower** one:

> the core test-scope recommendation is unchanged but is now stated as conditional on the unverified
> runtime-vs-test classpath fact rather than as a settled conclusion

It gave up asserting, and reduced the decision to a single checkable fact. A compile probe settled
that fact in seconds, and it settled in favour of what had already shipped.

**That is the product: not a verdict, and not a better opinion, but "here is the one thing you should
go and verify."** A question is easier to act on than an answer you have to trust, and it is exactly
what a person deciding an architecture question needs.

Two consequences.

The insight-per-question score understates the tool, because a narrowed decision does not register as
an insight. It is not a new fact about the world; it is a smaller question. The 1-in-3 figure remains
the honest number for what was measured, and what was measured turns out not to be the whole value.

It also explains why one round is enough, more convincingly than the cost argument. Narrowing happens
in the architect's *response* to criticism, which is the third turn of a single round. Further rounds
would re-argue a claim already reduced to a fact-check, and the fact-check is faster and more reliable
than either model.

**Unchanged by this:** the verdict. One round, no convergence subsystem, later phases dropped. Also
unchanged is the caveat below about the challenger's constrained output budget.

Note the honest limit: this rests on one artifact. It is a reframing worth recording, not a second
experiment, and it has not been tested on a question nobody had already decided.

## Caveats worth stating

The challenger ran at roughly a quarter of the control's output budget, forced by the free tier
rather than chosen. A larger budget might change the ratio, and the honest reading is that this
experiment establishes a floor for the debate's value, not a ceiling.

Three questions is a small sample, all from one project and one domain. The two errors were both
caught only because the reader already knew the answer — which is precisely the situation a decision
support tool is not designed for.
