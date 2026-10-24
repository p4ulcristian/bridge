# Steps are a list over one context

> **Changed in part** by [The steps and the answer's shape are groundwork-steps's](2026-10-24-the-steps-and-the-answers-shape-are-groundwork-steps-and-the-browsers-routing-is-groundwork-screens.md) (2026-10-24): the steps, their functions and the context's keys are groundwork-steps's (`groundwork-steps.api`); the answer is at `:groundwork-steps/answer`.

> **Changed in part** by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) (2026-10-22): steps are a handler's steps in mailroom (`mailroom.api`), the name kept; the answer is at `:mailroom/answer`.

> Replaces [Work is a vector of steps over one map](2026-10-22-work-is-a-vector-of-steps-over-one-map.md): no effects, no `:leave` or `:error`, no driver; a `reduce`.

**Decision:** work runs as a vector of steps over one map, the context. A step is `{:name a-keyword :enter (fn [context] context)}`. `run-steps-until-answered` is a `reduce`: each step gets the context the one before returned, and once a step has called `add-answer-to-context`, the reduce stops (`reduced`) and the steps after it don't run. A step does its own work (SQL, reading a body) and puts the result in the context.

**Why:** a reduce is plain Clojure: nothing to learn but the step's map. The steps are a vector, so the order of the work can be printed and checked. Every part of it is understood; the rest (effects, leaving, errors) comes when a real need asks for it.

**The price:** a step that does outside work is tied to it in tests (a database for a SQL step). Anything thrown leaves the reduce as it would any function; no step cleans up on the way out.

**Instead of:**
- effects, `:leave`/`:error` phases and a driver (the decision this replaces): steps run on any runtime and test without a database, but more to understand than is needed yet
- Ring middleware inside too: plain functions, but a wrapped stack can't be printed or checked
