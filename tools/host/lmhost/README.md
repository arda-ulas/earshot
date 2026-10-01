# lmhost: spec and grammar files

```bash
lmhost [--threads N] [--ctx N] MODEL SPEC GRAMMAR
```

`lmhost` reads one user text per stdin line and writes one JSON object per stdout line:

```text
in:  I'm freezing
out: {"raw":"{\"intent\":\"warmer\"}","ms":228.810}
```

Defaults match the app: `--threads 2`, `--ctx 1024` (the batch size equals the context size).
The input is used as given: the app's `LmInterpreter` cuts the text to 200 characters before calling
the model, so a caller that wants the app's behaviour does the same.

## Spec file

UTF-8 text, one entry per line, `key<TAB>value`. Blank lines and lines starting with `#` are
ignored; anything else without a tab, or with an unknown key, stops `lmhost` with an error.

| Key | Meaning | App source |
|---|---|---|
| `system` | the system prompt; must be the first entry, exactly once | `LmWireFormat.SYSTEM_PROMPT` |
| `user`, `assistant` | few-shot examples, in order, as alternating turns | `LmWireFormat.EXAMPLES` |
| `assistant_prefix` | text placed right after the assistant turn marker (optional) | `LmWireFormat.ASSISTANT_PREFIX` |
| `max_tokens` | generation cap, 1 to 256 (optional, default 16) | `LmWireFormat.MAX_TOKENS` |

In values, `\n` stands for a newline, `\t` for a tab and `\\` for a backslash. A backslash before
any other character is kept as is. Files written by `docs/lm-eval/bench.py` (which only escapes
newlines) read the same way, as long as their text has no backslashes.

The `system`, `user` and `assistant` entries form the fixed prompt prefix, which is formatted with
the model's chat template and decoded once. For each request the user's text is added as a final user
turn, the assistant marker and `assistant_prefix` are appended, and only that part is decoded.

Example (the system prompt shortened here; a real spec has the full text from `LmWireFormat`):

```text
# from core LmWireFormat
system	Map the driver's words to one intent. Output only JSON like {"intent":"warmer"}.\nDecide in this order:\n...
user	I'm freezing
assistant	{"intent":"warmer"}
user	the sun is baking me
assistant	{"intent":"cooler"}
assistant_prefix	<think>\n\n</think>\n\n
max_tokens	16
```

The regression harness writes this file from the Kotlin constants on every run, so it cannot drift
from the app. To write one by hand, copy the values from `LmWireFormat.kt` (the system prompt after
`trimIndent`) or from `docs/lm-eval/variants/intent-qwen3-best.json`, which holds the same prompt and
examples.

## Grammar file

A GBNF grammar with a `root` rule, passed to llama.cpp as is. For the app it is
`LmWireFormat.GRAMMAR`:

```text
root ::= "{\"intent\":\"" intent "\"}"
intent ::= "warmer" | "cooler" | "ac_on" | "ac_off" | "defrost_front_on" | "defrost_rear_on" | "query_speed" | "query_gear" | "query_cabin" | "out_of_domain"
```

A grammar that does not parse makes the warm-up request fail, and `lmhost` exits with status 3
before reading stdin.

## Exit status

0 at end of input; 1 bad arguments; 2 spec, grammar or model could not be read; 3 warm-up failed.
