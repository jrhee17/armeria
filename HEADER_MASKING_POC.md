# Header masking proof of concept

Branch: `poc/header-masking-functions`, based on upstream `main` at
`18ec9d2699` (fetched 2026-11-09).

## API

```java
final HeaderMaskingFunction policy =
        HeaderMaskingFunction.builder()
                             .maskingFunction("x-api-key", HeaderMaskingFunction.of())
                             .maskingFunction("x-token", (name, value) -> "token-" + value.length())
                             .maskingFunction("x-internal", (name, value) -> null)
                             .maskQueryParams("token", "ssn")
                             .build();

final HeadersSanitizer<String> text =
        HeadersSanitizer.builderForText().headerMaskingFunction(policy).build();
final HeadersSanitizer<JsonNode> json =
        HeadersSanitizer.builderForJson().headerMaskingFunction(policy).build();

LogFormatter.builderForText().requestHeadersSanitizer(text).build();
```

The same policy works in both output formats. The header builder has a
`maskQueryParams(String...)` convenience method that registers an ordinary
`:path` rule. There is no separate query processing stage or `otherwise` callback.

## Defined behavior

- Header names match case-insensitively.
- **Last registration wins** for a name. Each value goes through only that function.
- The builder starts with ordinary `****` rules for `authorization`, `cookie`,
  `set-cookie`, `proxy-authorization`, `athenz-role-auth`, and `yahoo-role-auth`.
  These share the existing default name set and can be overridden individually.
- Unregistered header values pass through unchanged.
- A `null` rule result removes that value. The whole header disappears if no
  values remain. End-of-stream output is preserved when all headers are removed.
- Built policies copy the registrations. Changing a builder later does not
  affect existing policies; callback state remains the caller's responsibility.
- `HeaderMaskingFunction.of()` continues to replace every value passed to it
  with `****`, regardless of the name.

A new `headerMaskingFunction(policy)` receives every header value and takes
precedence over all deprecated sanitizer settings, regardless of setter order.
Repeated calls to this setter replace the complete policy.

## Query masking is an ordinary header function

`HeaderMaskingFunction.ofQueryParams("token", "ssn")` returns a
`HeaderMaskingFunction`. Register it for `:path` to mask request query parameters,
or for a header such as `location` to mask query parameters in that URI value.
The package-private implementation implements `HeaderMaskingFunction` directly;
there is no separate parser helper or headers wrapper.

Query names are matched case-sensitively after decoding. Each matched occurrence
is masked. Unmatched components retain their original spelling and encoding,
and fragments are preserved. Passing no names masks nothing.

The builder convenience method is exactly shorthand for registering that
function for `:path`:

```java
HeaderMaskingFunction.builder().maskQueryParams("token", "ssn");
// Equivalent registration:
HeaderMaskingFunction.builder()
                     .maskingFunction(HttpHeaderNames.PATH,
                                      HeaderMaskingFunction.ofQueryParams("token", "ssn"));
```

Repeated convenience calls replace the entire `:path` rule.
`maskQueryParams("a").maskQueryParams("b")` masks only `b`.
A later explicit path rule replaces the query masker, and a later convenience
call replaces an explicit path rule. Passing no names replaces the current
path rule with a function that leaves the URI unchanged.

For custom query transformations, the callback overload handles every query
parameter:

```java
HeaderMaskingFunction.ofQueryParams((name, value) -> {
    if (name.equals("internal")) {
        return null;
    }
    return name.equals("token") ? "[redacted]" : value;
});
```

Callbacks are `BiFunction<String, String, String>` functions that can return
`null`. They receive decoded names and values; returned values are encoded.
Returning `null` removes that parameter occurrence. Removing all components
removes the query delimiter, leaving the path itself.

This overload re-encodes returned values, even when returning the decoded input.
Use the names overload to preserve unmatched raw components exactly. There is
no map overload.

Because query masking is an ordinary rule, another registration for `:path`
replaces it. There is no implicit extra query masking stage. Compose explicitly
inside a single rule when multiple path transformations are needed:

```java
final HeaderMaskingFunction queries = HeaderMaskingFunction.ofQueryParams("token");
HeaderMaskingFunction.builder()
                     .maskingFunction(HttpHeaderNames.PATH,
                                      (name, value) -> "logged:" + queries.mask(name, value))
                     .build();
```

## Compatibility

The deprecated `sensitiveHeaders(...)` and `maskingFunction(...)` methods keep
their existing logic when no complete new policy is configured. The first
explicit sensitive set replaces the defaults; repeated calls add names. An
empty explicit set selects no headers. The old masking function runs only on
that set; other values pass through.

Existing callers need no migration. To migrate, register the old function for
each name in the old set. The new builder retains standard default rules;
register identity functions for any standard headers deliberately excluded by
an explicit old set when an exact migration is needed.

This rewrite starts from main and adds none of the reviewed PR's formatter
query APIs or `headersMaskingFunction` API. The earlier working state is saved
in the Git stash named `header-masking PoC before rewrite from main` and in
`/private/tmp/armeria-header-masking-before-main-fqh6fu6n`.

## Verification

All 81 focused tests passed, with no failures, errors, or skipped tests.
Main and test Checkstyle passed.

Focused tests cover default and legacy behavior,
case-insensitive replacement, per-value removal, snapshots, precedence between
old and new APIs, query decoding and encoding, repeated names, fragments,
URI headers, explicit composition, repeated convenience calls, mixed explicit
and convenience path rules, copied parameter names, and text/JSON formatter integration.

Performance has not been benchmarked.
