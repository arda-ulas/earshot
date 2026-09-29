# Working with coding agents in this repo

1. **`main` changes only through pull requests with CI green.** One scope per branch.
2. **The policy is the only path to the vehicle.** Nothing may call `VehicleGateway.write` except the
   turn engine acting on a policy verdict. Language-model output is never trusted: it is parsed
   strictly, re-validated, and always confirmed.
3. **Verify library and tool versions against current documentation; don't guess.** Versions live in
   `gradle/libs.versions.toml`; native sources and models are pinned by SHA-256.
4. **Every safety or security requirement has a verifying test** annotated `@Verifies("SR-n")`, or is
   listed as manual in `docs/requirements.md`. `scripts/traceability.py --check` enforces it in CI.
5. **No models, audio or traces in git.** No `INTERNET` permission. No secrets anywhere.
6. **No overstatement in docs.** Describe what the code does and does not do. Never describe the project
   as compliant, certified, ASIL-rated, or production-grade.
7. **AI-assisted work is read before commit** and logged in `docs/ai-log.md`: what was asked, what was
   produced, what was changed or rejected, and how it was verified.
8. **Label evidence honestly.** Results from synthetic clips are `clip`, results from a person speaking
   are `mic`; latency is always labelled with the host it was measured on.
