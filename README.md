# Overview

Brokk (the [Norse god of the forge](https://en.wikipedia.org/wiki/Brokkr))
is the first code assistant that understands code semantically, not just
as chunks of text.  Brokk is designed to allow LLMs to work effectively
on large codebases that cannot be jammed entirely into working context.

# What Brokk can do

1. Automatically determine the most-related classes to your working context and summarize them
1. Parse a stacktrace and add source for all the methods to your context
1. Add source for all the usages of a class, field, or method to your context
1. Pull in "anonymous" context pieces from external commands with `$$` or with `/paste`
1. Build/lint your project and ask the LLM to fix errors autonomously

These allow some simple but powerful patterns:
- "Here is the diff for commit X, which introduced a regression.  Here is the stacktrace
  of the error and the full source of the methods involved.  Find the bug."
- "Here are the usages of Foo.bar.  Is parameter zep always loaded from cache?"

# Brokk with o1pro

Brokk is particularly useful when making complex, multi-file edits with o1pro.

The `prepare` command will ask your normal editing model what extra context you might want to include
in your request.  Then use `copy` to pull all the content, including Brokk's prompts, into your clipboard.
Paste into o1pro and add your request at the bottom.  Then paste o1pro's response back into
Brokk and it will apply the edits.

# What Brokk can do very soon

1. Automerge
1. Better autocomplete of identifiers while composing a request to the LLM.
   Right now Brokk requires you to complete a classname before it will
   offer to complete members of that class.  Just stuffing all the identifiers
   into the autocomplete would be better.

# Current status

Code intelligence only supports Java right now, but the non-intelligent pieces
work fine in the meantime (I've edited my Python project
[llmap](https://github.com/jbellis/llmap) with Brokk).

### Lombok fine print!

Joern (the code intelligence engine) needs to run delombok before it can analyze anything.
Delombok is unusably slow for anything but trivial projects, making Brokk a poor fit for
Lombok-using codebases.

# Getting started

Requirements: Java 21+

1. `cd /path/to/my/project`
2. `export ANTHROPIC_API_KEY=xxy`
   - or, `export OPENAI_API_KEY=xxy`
   - other providers and models are technically supported, making them easier to use is high priority.
     In the meantime, look at Models.java for how to set up a ~/.config/brokk/brokk.yml file with
     your preferred option if the defaults don't work for you.
1. `java -jar /path/to/brokk/brokk-0.1.jar`

There is a [Brokk Discord](https://discord.gg/ugXqhRem) for questions and suggestions.

# Finer points on some commands

- Most context commands can act on anonymous fragments as well as files.  `/add 1`, `/read 1`, `/summ 1`
  will add, read, or summarize all the files referenced in anonymous context fragment 1.
  (Brokk will recognize files
  if they are specified by their full path from the git root, or if the fragment comes from Brokk's
  own code analysis.)
  `copy 1` will copy just the contents of anonymous fragment 1.
- Brokk doesn't automatically detect filesystem changes, not even the ones it makes itself.
  You will need to `/refresh` after making changes in your code for Brokk to see them.  You will
  also need `/refresh` if you make changes to the git repo outside Brokk.
- Brokk doesn't offer automatic running of tests (too much variance in what you might want it to do).
  Instead, Brokk has the `send` command which will send the output of the last `$` command to
  the LLM as instructions.

# FAQ

1. What code intelligence library does Brokk use?

Brokk uses [Joern](https://github.com/joernio/joern), an industrial-strength code analysis engine from [Qwiet](qwiet.ai) (formerly Shiftleft).  I spent multiple days evaluating all the relevant options and Joern is the only one powerful enough (and fast enough) to do what I want, so huge thanks to the team at Qwiet for that!
