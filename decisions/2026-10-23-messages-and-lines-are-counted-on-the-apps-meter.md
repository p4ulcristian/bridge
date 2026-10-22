# Each message and open line is counted on the app's meter

> Changes in part [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (the settings gain `:metric-buckets`) and [The app passes its log](2026-10-22-the-app-passes-its-log.md) (`make-routes-for-messages`' options gain `:meter`). Since v0.7.0, on meter v0.1.2.

**Decision:** `make-routes-for-messages` takes the app's meter (meter's `make-meter`) as a required option, `:meter`, never nil, declares mailroom's metrics on it, and counts into it:

| metric | type | labels |
|---|---|---|
| `mailroom_messages_total` | counter | `name`: the handler table's names; `pipe`: `http`, `ws`; `outcome`: `ok`, `invalid`, `refused`, `too-many`, `failed`, `unfit`, `no-handler` |
| `mailroom_answer_seconds` | histogram | `name` |
| `mailroom_open_lines` | gauge, +1 when a line opens, -1 when it closes | `pipe`: `sse`, `ws` |

- **Each name in a message is one count**, after it is answered: `ok` its answer, data; `invalid` params that didn't fit (`{:errors …}`); `refused` a step's `{:error …}`, or no step answered; `too-many` over its rate limit; `failed` a step threw; `unfit` an answer that didn't fit its `:answer-shape`; `no-handler` a name the table hasn't. Its time, from before the rate limit to its answer, is recorded under its name.
- **The names' set is the handler table's keys.** A name no handler has is counted as `"other"`, so a scanner's names make no series; meter says the first one at `:warn`.
- **`pipe` on the messages is `http` or `ws`**: sse carries no message to the server. A body that isn't a JSON object of names (400) has no name and isn't counted.
- **The buckets are a setting**, `:metric-buckets {:answer-seconds [0.005 … 10]}`, required, in seconds; meter checks they go up.
- **A meter takes one table.** `make-routes-for-messages` called again on the same meter with another table is refused by meter (`:meter/bad-metrics`, declared differently); with the same table, it is no change.
- **`answer-message` takes no meter** and counts nothing: it answers outside a server, in tests and the REPL, where a count would be in no app's metrics. `start-server!` takes none either: http-kit's errors go to its log.

**Why:** an app needs to see which messages it answers, how they fail and how long they take, without reading the log; Iron Rainbow counted its requests by route for that. The table's names are a closed set mailroom already holds, so the label can't grow with what browsers send (meter's closed sets). The outcomes are the answers mailroom itself tells apart; the open lines say how many browsers listen.

**The price:** every app writes `:meter` and `:metric-buckets`. A meter serves one handler table. An sse line a browser leaves isn't seen as closed by http-kit 2.8.1 (its `:on-close` isn't called), so `mailroom_open_lines{pipe="sse"}` counts it until the server stops; ws lines are.

**Instead of:**
- the request's path as a label: one address for every message, and a scanner's paths are what meter's closed sets keep out
- a `pipe` value `sse` on the messages: none come that way
- counting in `answer-message` under its own pipe: its counts would mix tests into an app's metrics
- the outcome told by the answer's text: a step's refusal and mailroom's own would share a series with any text a step writes
