# Contributing to OSHI

Thank you for looking. This is a solo project, so a good issue is worth as much
as a pull request.

## What helps most

**Cryptographic review.** The files in [`src/`](src/) are the ones that matter:
the Double Ratchet, X3DH, and the mesh routing. If you find a flaw there, you
will have done more for this project than any feature.

**Reproducible bug reports.** "It doesn't work" cannot be fixed. What you did,
what you expected, what happened, and your iOS version can be.

**Protocol questions.** If [`docs/protocol.md`](docs/protocol.md) is unclear or
wrong, that is a bug in the document.

## What to expect

I answer issues, but I am one person with a day job. Give it a few days before
assuming silence means indifference.

Pull requests to `src/` are reviewed against the published protocol
documentation. If your change alters the wire format, the documentation has to
change with it — a protocol whose spec and code disagree is worse than one with
no spec.

## Security bugs do not go here

Do not open a public issue for a vulnerability. Write to
**security@oshi-messenger.com** instead. See [`Security.md`](Security.md) for the
threat model and the bug bounty.

## Ground rules

Be civil. Disagree about the code, not about the person. That is the whole code
of conduct, and it has been enough so far.
