# Release signing

`teal-release.p12` signs the release APKs attached to every tagged build.

## This key is public, deliberately

It is committed to a public repository, which means anyone can build an APK that
Android treats as a legitimate update to Teal. That risk was accepted in
exchange for not needing secrets infrastructure to produce a signed release.

What limits the damage:

- The **certificate fingerprint below is published here and in every release
  note.** It cannot be forged, so an install can be checked against it:

      apksigner verify --print-certs Teal.apk

  A matching SHA-256 signer fingerprint means the APK came from this key.

- Teal requests no dangerous permissions beyond storage and notifications, holds
  no credentials, and its downloads land in public storage — so a forged update
  has less to steal than most apps would offer.

- Every release also ships `SHA256SUMS.txt`, so a specific APK can be matched to
  a specific tag.

**Certificate SHA-256 fingerprint**

    A4:81:D4:A5:53:6C:68:54:D4:DC:B1:BF:FB:7F:BE:2B:FB:52:2A:4B:DD:16:E0:80:2A:E2:34:3B:0B:41:5F:48

Subject `CN=Teal, OU=Teal, O=Teal, L=Mumbai, ST=Maharashtra, C=IN`, valid
2026-10-30 to 2056-10-29. PKCS12, RSA 2048, 3DES/SHA-1 so any JDK can read it.

## Moving to a private key later

The build prefers the `TEAL_KEYSTORE_B64` repository secret when one exists and
falls back to the committed file otherwise:

```yaml
- name: Prefer the private signing key when one is configured
  env:
    KEYSTORE_B64: ${{ secrets.TEAL_KEYSTORE_B64 }}
  run: |
    [ -n "$KEYSTORE_B64" ] && printf '%s' "$KEYSTORE_B64" | base64 -d > signing/teal-release.p12
```

Add that secret with **this same keystore** and the key stops being public while
every existing install keeps updating normally. Adding a *different* keystore
changes the signature, which forces every user to uninstall first.

## Losing the key

It cannot be recreated. If it is lost, existing installs cannot be updated —
users have to uninstall and lose their history. Keep a copy outside the
repository.
