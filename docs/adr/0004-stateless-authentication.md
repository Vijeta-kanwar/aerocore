# ADR 0004: Authenticate with stateless JWTs

## Status

Accepted — 2026-08

## Context

Before this, every endpoint was public. Anyone who could guess a booking id could cancel
someone else's flight, and anyone could list any passenger's bookings by typing their email
address. The README listed it under known limits; this is where it stopped being true.

The application runs as three replicas behind a load balancer. That rules out the obvious
approach: a session held in pod A's memory is invisible to pods B and C, so round-robin
routing logs a user out on roughly two requests in three.

Two workarounds exist and both cost more than they are worth here. Sticky sessions tie a user
to one pod, which removes the load balancer's freedom to route and breaks when Kubernetes
kills that pod during a rollout. A shared session store — Redis — works, but adds a service
to run, monitor and pay for in order to hold state that need not exist.

## Decision

A signed JWT, verified independently by whichever pod receives the request. No session store,
no lookup, no coordination.

The payload carries a user id, a role and an expiry. It is base64, not encryption — anyone can
decode it in a browser console — so it holds nothing secret. What the signature buys is not
privacy but forgery resistance: editing the role claim to `ADMIN` invalidates the HMAC,
because recomputing it needs a secret the holder does not have.

Authorization is split from authentication. The filter only establishes who is calling and
lets the request through either way; the security rules decide whether anonymous is
acceptable. That is what lets flight search stay public — an airline publishes its timetable —
while booking and cancellation do not.

Ownership is checked in the service layer rather than the controller, because it is a business
rule about bookings rather than a property of HTTP. A request for a booking that belongs to
someone else returns **404, not 403**: a 403 confirms the booking exists, which turns id
enumeration into a way to count the system's bookings. To someone who does not own it, a
booking they cannot see and a booking that does not exist should be indistinguishable.

The browser keeps the token in `localStorage`, so a page refresh does not sign the user out.
For something people will click around in for two minutes, being logged out on every reload
means the project does not get looked at properly.

## Consequences

Any pod serves any request, and scaling from one replica to three needs no session
infrastructure at all.

The token in `localStorage` is readable by any cross-site scripting on the page. What bounds
that is a 30-minute expiry and the fact that every server-sourced string passes through
`escapeHtml` before touching `innerHTML`. It is a real exposure, accepted for a demo with a
named upgrade path: an httpOnly cookie, which JavaScript cannot read at all, with CSRF
protection brought back — a bearer token does not need CSRF because it must be attached
deliberately by our own code, but a cookie the browser sends automatically does.

There is no revocation. Signing out stops sending the token; it does not invalidate it, and a
stolen one works until it expires. The standard answer is a short access token plus a
long-lived refresh token, and the asymmetry there is deliberate rather than inconsistent: the
refresh token is stored server-side and checked on every use, so it can be revoked, while the
access token stays stateless. The frequent operation pays nothing, the rare one pays for a
database read.

Registration always creates a `USER`. Admins are made by hand, because an endpoint that can
grant its own caller administrative rights is not a feature.
