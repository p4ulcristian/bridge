# :not-found is the app's

> Built 2026-10-23 (v0.7.7), recorded later. Asked for by groundwork's [A page says its own head and body through the app's render-page](https://github.com/p4ulcristian/groundwork/blob/main/decisions/2026-10-23-a-page-says-its-own-head-and-body-through-the-apps-render-page.md): the app's not-found page among its pages. Changes in part [A route is a Ring handler made from steps](2026-10-22-a-route-is-a-ring-handler-made-from-steps.md), [web becomes transport and routes](2026-10-22-transport-and-routes.md) and [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): a request no route matched was always answered 404.

**Decision:** `make-ring-handler-from-routes routes options` takes `:not-found`, required: nil, and a request no route and no file answers is answered `404` with no body, as before; or `(fn [request])`, whose answer it is (the app's not-found page). A route without the request's method is still `405`, a handler that answered nil `406`.

```clojure
(bridge/make-ring-handler-from-routes routes {:files nil :not-found nil})          ; 404, empty
(bridge/make-ring-handler-from-routes routes {:files nil :not-found render-404})   ; the app's answer
```

**Why:** what a person sees at an address the app doesn't have is the app's, like every other page; an empty 404 is a choice only the app can make for itself.

**The price:** every caller writes `:not-found` out, nil for the empty 404.

**Instead of:**
- **the empty 404 always**: the app's not-found page could not be drawn
- **`:not-found` left out for the empty 404**: a hidden default; nil is written out instead
