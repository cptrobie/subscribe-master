# White-Label Branding and Multipart Email — Design Notes (Draft)

Captures decisions and groundwork from `FR-30`'s design discussion, ahead of any
implementation. **Not yet a requirement** — needs its own FR ID (likely Wave 8,
alongside `FR-20`'s notification work) before it's built. **Not sourced from
`task.pdf`.**

## Context

A buyer who purchases the application runs their own deployment and wants it to
present as *their* product. That's **per-deployment** branding — one set of
values per running instance, set in configuration — not per-tenant or
per-subscription-provider branding, which would make branding per-message data
instead.

## What FR-30 does now

- **Plain-text email only.** No HTML, no logo.
- **Nothing brand- or environment-specific is hardcoded.** Product name, sender
  and the verification link's base URL all come from configuration, so a buyer
  can rebrand without code changes, and so the later HTML work doesn't need to
  hunt down literals:

```yaml
app:
  branding:
    product-name: Subscribe Master
  mail:
    from-address: no-reply@example.com
    from-name: ${app.branding.product-name}
  email-verification:
    link-base-url: ...          # per environment; prod has no default
    token-ttl: PT24H
```

| Properties class | Package | Read by |
|---|---|---|
| `BrandingProperties` (`app.branding.*`) | `support` | `auth` (email text), later the email layout |
| `EmailProperties` (`app.mail.*`) | `message/email` (internal) | `EmailSender` only |
| `EmailVerificationProperties` (`app.email-verification.*`) | `auth` | token TTL, link building |

`link-base-url` must always come from config, never from the incoming request's
`Host` header — that header is client-controlled, and deriving links from it is
a known host-header-injection attack.

## White-label scope

What a buyer would expect to change, beyond the product name:

- Sender display name and address (`app.mail.*`) — the address must be on a
  domain the buyer controls and has set up SPF/DKIM/DMARC for, or mail lands in
  spam. That's deployment/DNS work on the buyer's side, not application code,
  but worth stating in deployment docs.
- Logo, primary colour(s).
- Support contact (email/URL) shown in email footers.
- Company legal name and postal address in footers — payment reminders
  (`FR-19`/`FR-20`) are closer to commercial messages than verification emails
  are; confirm with the buyer's legal requirements (e.g. CAN-SPAM) whether a
  postal address is required.
- Outside email: Swagger/OpenAPI title (`OpenApiConfig`), any product name in
  API error messages. Not email work, but the same `app.branding.*` should feed
  them so there's one source of truth.

Suggested additions to `app.branding.*` when this is built: `logo` (classpath
resource or URL), `primary-color`, `support-email`, `support-url`,
`company-name`, `company-address`.

## Multipart refactor — what changes

**Callers don't change.** They keep passing a plain-text `body` on
`OutboundMessage`. All format decisions stay inside `message/email`:

1. `EmailSender` builds a `MimeMessage` via `MimeMessageHelper(mimeMessage,
   true, "UTF-8")` (`true` = multipart) instead of a `SimpleMailMessage`.
2. `helper.setText(plainText, html)` — the text part is the caller's `body`
   unchanged; the HTML part is that same body wrapped in the buyer's branded
   layout. Clients show HTML when they can and fall back to text.
3. The layout is a template (Thymeleaf — `spring-boot-starter-thymeleaf`, under
   `templates/email/`), fed from `BrandingProperties`.

**Pitfalls to remember:**

- **Escape the body before putting it in HTML.** The body can contain
  customer-supplied values; inserting it raw is HTML injection. Thymeleaf's
  `th:text` escapes by default — never use `th:utext` for caller content.
- **Line breaks:** plain-text newlines need converting to `<br>`/`<p>` *after*
  escaping, and bare URLs (the verification link) should become `<a>` links or
  a button.
- **Email-client CSS is limited.** Many clients strip `<style>` blocks and
  ignore modern layout — inline styles and table-based layout. Test in
  Mailpit's HTML-check tab.
- **`addInline(...)` must be called after `setText(...)`** — `MimeMessageHelper`
  requires that order, or the inline image isn't linked correctly.

**Logo delivery — decide when building:**

| Approach | Pros | Cons |
|---|---|---|
| Inline CID (`addInline("logo", resource)`, `<img src="cid:logo">`) | Shows without remote-image permission; works offline | Larger messages; some clients also list it as an attachment |
| Hosted URL (`<img src="https://…">`) | Small messages; logo changeable after send | Many clients block remote images until the reader allows them; needs hosting |

## When the body-only design stops being enough

Wrapping a plain-text body in a shared layout works for simple messages
(verification links, 2FA codes, reminders). If a message type needs its own
structure — e.g. an invoice table — callers would instead pass a template name
plus a data model, and `OutboundMessage` would gain those as optional fields.
That's an addition, not a rework; don't design for it until a real message type
needs it.

## Testing

- **Local:** Mailpit (`compose.yaml`, SMTP 1025, UI 8025) shows both parts and
  flags HTML/CSS client-compatibility issues.
- **Automated:** GreenMail (or a mocked `JavaMailSender` capturing the
  `MimeMessage`) — assert both a `text/plain` and a `text/html` part exist, the
  HTML escapes injected markup, and the logo part is present when inline CID is
  used.

## SMS

Unaffected. `SmsSender` (`FR-37`) ignores `subject` and sends `body` as-is;
branding there is at most the product name in the text, from the same
`app.branding.product-name`.

## Open questions

- Logo delivery: inline CID vs hosted URL.
- Does any message type need its own template rather than the shared layout?
- Does the buyer need a postal address in reminder footers?
- FR ID and wave placement.
