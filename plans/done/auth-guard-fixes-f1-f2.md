# Auth guard fix round — F1 (admin-row escalation) and F2 (percent-encode bypass)

> DONE 2026-07-30. Two HIGH findings against the `47526ae` ownership guard,
> both reproduced end to end over HTTP against the prod-mode uberjar before
> fixing, and re-verified blocked after. Work order:
> `handoffs/personalist-auth-fixes.md`; findings:
> `handoffs/personalist-describe-cli-and-auth-review-findings.md`.
>
> **What did the trick.**
> - **F1 — a persona row named `admin` entered by email login granted blanket
>   exemption.** `POST /api/personas` takes an arbitrary `:id`, so any token
>   holder could mint a row `id=admin`; logging in *by email* (not `:id`) skips
>   the ADMIN_PASSWORD special-case and mints `create-token(:id)` →
>   `{:persona "admin"}`, which `owns-persona?` treated as admin. Two latches:
>   (1) the exemption now keys on an un-mintable `:admin true` claim stamped only
>   by the ADMIN_PASSWORD login (`create-admin-token`), never the string
>   "admin"; (2) `add-persona-handler` refuses the reserved id `admin` with 400.
>   `8530d85`.
> - **F2 — a percent-encoded persona segment bypassed the guard.** `wrap-auth`
>   read the persona off the raw `:uri`, but compojure URL-decodes route params
>   (`decode-route-params → ring.util.codec/url-decode`) before the handler, so
>   the guard saw `%74%61…` while the handler saw `targetx`. `persona-in-uri`
>   now decodes the segment with the *same* `url-decode`, so guard and handler
>   compare the identical string. Upper/lower hex decode alike (Jetty's
>   `:uri` uppercasing stops mattering); malformed escapes are left verbatim by
>   both sides (no throw, no bypass) and Jetty 12 rejects them with 400 before
>   routing anyway. The `persona-in-uri` docstring that asserted the raw string
>   was "safe to compare verbatim" — the false belief behind the bug — was
>   corrected. `241c840`.
>
> **Consequence stated:** an admin session holding a pre-fix token in
> `localStorage` lacks the new `:admin` claim and loses the cross-persona
> exemption until re-login. Accepted per the work order.
>
> **Tests.** Both exploits are regression tests in `auth_test.clj`, each shown
> failing against the pre-fix code (10 failing assertions) before the fix.
> Suite 42 tests / 258 assertions / 0 / 0; clj-kondo 0 errors / 5 pre-existing
> warnings.
>
> **Optional third item (describe test blind spots) — NOT done, deliberately.**
> Broadening the completeness scan to `et.pe.server` (blind spot b) is not
> cheap-and-clean: `et.pe.server/build-handler` is a public `*-handler` that is
> not a route, so the `*-handler`-as-route premise breaks there and closing it
> needs a fragile hardcoded exception. Blind spot (a) — a routed handler not
> named `*-handler` — and the deleted-route case are inherent to reading
> docstrings rather than the route table. Left as-is per the "otherwise leave it
> and say so" instruction.
