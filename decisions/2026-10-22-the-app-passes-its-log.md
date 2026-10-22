# The app passes its log; each message gets one that knows its request

> Changes in part [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (one log, `System.Logger` `mailroom`) and [Named messages over three addresses](2026-10-22-named-messages-over-three-addresses.md) (what a step's context holds). Since v0.3.0, on logbook v0.1.0.

**Decision:** mailroom writes no line of its own choosing. The app's log, `(log level event data)` as logbook makes it, is a required key, `:log`, wherever mailroom answers messages or runs the server, checked by malli as a function (`settings/log-entry`, `:mailroom/bad-options` naming `:log`):

| function | where `:log` goes | what is written |
|---|---|---|
| `make-routes-for-messages` | its options, beside `:wire`; given to logbook's `with-context` once there, so a function logbook didn't make is refused at once (`:logbook/not-a-log`) | each message's log is made from it |
| `answer-message` | its options, `{:wire wire :log log}` | the same, outside a server |
| `start-server!` | a new options map, last: `(start-server! ring-handler settings {:log log})` | http-kit's own errors, `:error :mailroom/server-error`, and warnings, `:warn :mailroom/server-warning`, `{:mailroom/http-kit-message … :error e}` |

**What every step may use.** For each message (one POST body, one ws message: every name in it), mailroom makes a log with a new random request id as its context, `(logbook/with-context log {:request-id "7f3a…"})`, and every step's context holds, beside the request, the name and the data:

```clojure
{:mailroom/log  the message's log, {:request-id "…"} in every line it writes
 :mailroom/wire the app's wire}
```

A step that learns more puts a log with more context back at `:mailroom/log`, for the steps after it: doorman's find-session step adds `:person-id`. A library a step calls is handed `(:mailroom/log context)`, so its lines carry the request too.

**A handler that throws** is written at `:error`, `:mailroom/handler-failed {:mailroom/message-name … :error e}`, with the log the steps had got to: mailroom notes the context each step returns (and the one `:run-steps-in` hands its `run`), so the line carries the person a step had added, not only the request id. The answer is "Something went wrong." as before.

**The request id is not in the answer.** It is in every line the message's work writes, and nowhere the browser sees.

**Why:** "Context is handed down as a value": the message is where the request starts, so mailroom is where its id is made, and the steps are how the work is handed down. Putting the log and the wire in the context means a step never reaches for a global to log or to write data, and the app's one wire and one log are the values in effect. A failure written with the person in its context answers "whose request broke" from the line alone.

**The price:** `make-routes-for-messages`, `answer-message` and `start-server!` take the log, `start-server!` a new options map; every caller of v0.2 changes. mailroom takes logbook (the server's side only). Each message makes one random UUID.

**Instead of:**
- the request id in the answer (`:mailroom/request-id` beside the names, or an `x-request-id` header): a person could quote it from the browser, but the answer is the protocol both ends read, ws has no headers, and "Something went wrong." is found from the person and the time in the errors a monitor pulls
- the id taken from the asker's `x-request-id` header: the asker could write anything, and two requests share one id
- one id per name in a message: the names of one message are one request from the browser
- the failure written with the message's first log: the person a step had found would be lost from the line that matters most
- `:log` on the line, for `send-message-on-sse-line!`: sending writes no line
