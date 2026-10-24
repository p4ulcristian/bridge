# A page has its query, #fragment and language; a move says how to scroll; leaving another page is asked first

> **Changed in part by** [The steps and the answer's shape are groundwork-steps's, and the browser's routing is groundwork-screens's](2026-10-24-the-steps-and-the-answers-shape-are-groundwork-steps-and-the-browsers-routing-is-groundwork-screens.md) (2026-10-24): following the address bar, moves, `finish-move!`, `make-address-of-page` and the scroll are groundwork-screens's; bridge keeps the server's routes for pages.

> **Changed in part** by parlor's [A move says whether it adds a history entry](https://github.com/p4ulcristian/parlor/blob/tidy-up/decisions/2026-10-23-a-move-says-whether-it-adds-a-history-entry.md) (2026-10-23, built on tidy-up, not released): `go-to-page!` takes the way, `:push` or `:replace`; and by parlor's [The unsaved-changes hold asks with the app's own dialog](https://github.com/p4ulcristian/parlor/blob/tidy-up/decisions/2026-10-23-the-hold-asks-with-the-apps-own-dialog.md) (2026-10-23, built on tidy-up, not released): `:may-leave?` is asked with the move, `(fn [move])`, and `finish-move!` finishes one it held back.

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> The user's decision, 2026-10-23 (house's "routing extras"). Since v0.6.0. Changes in part [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): `start-following-address!` takes options, `go-to-page!` and `make-address-of-page` a query. Carries over Groundwork v0.5.0's `groundwork.browser.routing` (house's tag v0.5.0): the address parsing, the history and the scrolling; the re-frame parts went to parlor.

**Decision:** the browser's routing (`mailroom.routes`) reads more of an address and follows the address bar itself, in place of reitit-frontend's `easy` (reitit's router still matches).

- **A page** is `{:name :page :path-params :query :query-errors :fragment :language}`. Query values are text; a page may declare a query shape, a malli map, which reads them into real types (`?page=2` is `2`); a value that doesn't fit is left out and named in `:query-errors`. `:query-shapes-of-pages` names every page and no other, nil for text, as every value is written out.
- **Languages come from the app's functions**: `:languages` is nil, or `{:split-language-from-path f :make-path-in-language f :language-of-page "en"}`, dictionary's functions in house. A page's `:language` is its address's prefix's, else the language the page came in. A move, and an address made, keep the language of the page shown.
- **`go-to-page!` and `make-address-of-page` take a query**, `{}` for none, a nil value leaving its key out.
- **Each move tells how to scroll** once the page is drawn: another page from the top, or at its `#fragment`'s element; a change of only the query, or nothing, where it is; only the `#fragment`, at its element; Back and Forward where that history entry was left. The positions are kept per entry (an index in the entry's state), in sessionStorage too when the page is left, so a reload finds its place; the browser's own restoring is turned off (`history.scrollRestoration = "manual"`). The app draws, so the app scrolls: `on-page` gets `(page scroll)`, and the app calls `scroll-as-told!` after drawing.
- **Leaving another page is asked first**: before a move to another page (another name, path params or language) by a link, `go-to-page!`, or Back and Forward, the app's `:may-leave?` is asked; no, and nothing moves, and Back and Forward are put back. A change of only the query or the `#fragment` isn't leaving and asks nothing. nil asks nothing.
- **A link to the address shown** replaces its history entry instead of adding one, as a browser does.

```clojure
(mailroom/start-following-address! pages {:on-page               (fn [page scroll] …)
                                          :query-shapes-of-pages {:orders/list [:map [:page {:optional true} :int]]
                                                                  :orders/order nil}
                                          :may-leave?            nil
                                          :languages             nil})
(mailroom/go-to-page! :orders/list {} {:page 2})
(mailroom/scroll-as-told! scroll)
```

**What stays fixed, and why:** the history entry's state key `mailroom` and the sessionStorage key `mailroom-scroll`: both ends are mailroom's. A link is followed in the app only when it is a plain click (the main button, no modifier key) on a link to the page's own origin, with no target and no download, to an address a page has; anything else is the browser's, as before.

**Why:** a filter in the address (`?stage=curing`) is part of what a page shows, and a link or a reload must bring it back; read by a shape, a screen gets numbers, not text to parse. Scrolling the way a browser does for documents (top on a new page, back where you were on Back) is what people expect, and only the routing knows which kind of move it was; only the app knows when the page is drawn. Asking before leaving belongs where the move is decided, so a link, a call and Back are all caught.

**The price:** an app writes `:query-shapes-of-pages` for every page, and `{}` in every `go-to-page!` and `make-address-of-page`. reitit-frontend's `easy` is gone: mailroom keeps the history itself (about a hundred lines). A position kept for Back is restored once the page is drawn; a page whose data comes later may be shorter then (parlor shows doorbell's kept answer at once, so it seldom is).

**Instead of:**
- the query as text only: every screen would parse its own
- the browser's scroll restoring (`auto`): it restores before the app has drawn the page it goes back to
- the question before leaving in the app's links: Back and the address bar would get past it
- languages inside mailroom: mailroom would hold dictionary's rules
