# mailroom is its own library

> **Changed in part** by [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md) (2026-10-22, v0.1.1): the four hooks are required, nil written out where nothing runs; the stop wait, the ws reopen delay and the rate-limit cleanup threshold are settings, not constants.

> Changes in part [Named messages over three addresses](2026-10-22-named-messages-over-three-addresses.md), [web has http, sse and ws](2026-10-22-web-has-http-sse-and-ws.md), [web becomes transport and routes](2026-10-22-transport-and-routes.md), [An answer may carry notes](2026-10-22-an-answer-may-carry-notes.md) and [Steps are a list over one context](2026-10-22-steps-are-a-list-over-one-context.md): their names, their keys, and every default they had.

**Decision:** mailroom is a library of its own, in its own public repository, under the MIT license; an app or a framework takes it from git by tag. It was three of Groundwork's libraries: the server's transport (`groundwork.server.transport`), the browser's (`groundwork.browser.transport`) and steps (`groundwork.server.steps`). Now it is the namespace `mailroom`:

- **One public namespace, `mailroom.api`, a `.clj` for the server and a `.cljs` for the browser**, as wire splits its languages; its parts are `mailroom.*`, each a `.clj`, a `.cljs` or both (`http`, `sse`, `ws`, `routes`, `messages`; `server`, `limits`, `settings`, `steps` on the server; `shapes` in the browser).
- **Steps moved in, as a handler's steps; the name is kept.** A handler's `:steps` key, each step `{:name … :enter (fn [context] context)}`, and `run-steps-until-answered`, `add-answer-to-context`, `does-context-have-answer?` are as they were, now in `mailroom.api`; the answer is kept at `:mailroom/answer`.
- **No defaults.** Three were hidden in transport; each is now written out by the app:
  - the three addresses (`/groundwork/http`, `/groundwork/sse`, `/groundwork/ws` before) are the setting `:addresses`;
  - the ping (every 25 s before, in sse and ws) is the setting `:ping-every-ms`;
  - the asker key for rate limits (the request's `:remote-addr` before) is the required option `:find-rate-limit-key`.

  The settings are one malli shape, `settings-shape`, every key required, the map closed, nil written out where it is a choice:

  ```clojure
  [:map {:closed true}
   [:ip :string]
   [:port :int]
   [:client-address-header [:maybe :string]]
   [:addresses [:map {:closed true} [:http :string] [:sse :string] [:ws :string]]]
   [:ping-every-ms [:int {:min 1}]]]
  ```

  `make-routes-for-messages` takes `handlers settings options` and `start-server!` takes `ring-handler settings`; each checks the settings first, and `make-routes-for-messages` its options too (`:find-rate-limit-key` a function, required). `make-ring-handler-from-routes` requires `:files-folder`, nil for none. A missing or unknown key refuses with every problem at once, each named (`:mailroom/bad-settings`, `:mailroom/bad-options`). The four hooks (`:on-sse-open`, `:on-sse-close`, `:on-ws-open`, `:on-ws-close`) stay optional: one left out means nothing runs then, as Groundwork's "Settings are required data" keeps hooks.
- **The asker key follows the settings.** The old fallback, the request's `:remote-addr`, is wrong behind a proxy: every asker comes from the proxy's address, so all of them shared one bucket and one busy user stopped everyone. The settings already say where the asker's address is (`:client-address-header`), and `find-client-address` reads it; an app's `:find-rate-limit-key` uses it, e.g. `#(mailroom/find-client-address % (:client-address-header settings))`, or a session's key first and that address else.
- **It knows nothing of who uses it:** the context's keys are `:mailroom/request`, `:mailroom/message-name`, `:mailroom/message-data`, `:mailroom/notes`; a line is `{:mailroom/channel … :mailroom/request …}`; notes go out under `:mailroom/notes`, the ws ping is `{:mailroom/ping {}}`, the browser's refusals and errors are `:mailroom/bad-params` and `:mailroom/errors`. Nothing names Groundwork's libraries.

**Why:** the three are one idea: named messages come in, a handler's steps answer them, the answers go out. None needed anything of Groundwork. Steps live in mailroom, not in a library of their own, because every list of steps is run by mailroom: the other libraries write steps only for a handler (a session check, an access check, a note of the tables read), so a steps library alone would have one user. "A handler's steps" says what they are inside mailroom: the message's processing, one message in, one answer out; so the name stays. No defaults, because Groundwork's decisions "Settings are required data" and "Each library brings its own settings" hold for every library: the app's settings tell the whole story, and a value in effect is never invisible. The name: the mailroom is the room of the house where mail comes in, is sorted by name and handled, and answers go out; it stands beside the cellar, the backroom and the showroom.

**The price:** an app writes three more settings and one more option than before, each nearly always the same. A change to mailroom is a new tag, and the app moves to it on purpose. Some timings stay constants, not settings: a stop waits up to 10 s, a ws line in the browser reopens after 1 s.

**Instead of:**
- steps as a library of its own: one user, and one more repository to tag
- renaming steps to chain, process or pipeline: "a handler's steps" already says what they are
- defaults the app may override: a good value without thought, but the value in effect is invisible in the app's code, and the rate-limit one was wrong behind a proxy
- staying three folders in Groundwork's tree: no app can take them alone, and nothing proves their deps
- the names intercom, hallway and transport: mailroom was chosen over them
