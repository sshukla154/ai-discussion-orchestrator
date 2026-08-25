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

**Still open:** the atomicity gap above, which is a genuine defect rather than a design preference.

## Caveats worth stating

The challenger ran at roughly a quarter of the control's output budget, forced by the free tier
rather than chosen. A larger budget might change the ratio, and the honest reading is that this
experiment establishes a floor for the debate's value, not a ceiling.

Three questions is a small sample, all from one project and one domain. The two errors were both
caught only because the reader already knew the answer — which is precisely the situation a decision
support tool is not designed for.
