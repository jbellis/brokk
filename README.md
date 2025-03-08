# Overview

Brokk (the [Norse god of the forge](https://en.wikipedia.org/wiki/Brokkr))
is the first code assistant that understands code semantically, not just
as chunks of text.  Brokk is designed to allow LLMs to work effectively
on large codebases that cannot be jammed entirely into working context.

# What Brokk can do

1. Ridiculously good agentic search / code retrieval. Better than Claude Code, better than Sourcegraph,
   better than Augment Code.  TODO link examples
1. Automatically determine the most-related classes to your working context and summarize them
1. Parse a stacktrace and add source for all the methods to your context
1. Add source for all the usages of a class, field, or method to your context
1. Pull in "anonymous" context pieces from external commands with `$$` or with `/paste`
1. Build/lint your project and ask the LLM to fix errors autonomously

These allow some simple but powerful patterns:
- "Here is the diff for commit X, which introduced a regression.  Here is the stacktrace
  of the error and the full source of the methods involved.  Find the bug."
- "Here are the usages of Foo.bar.  Is parameter zep always loaded from cache?"

# Using Brokk

When you start Brokk, you’ll see four main areas:

![image](https://github.com/user-attachments/assets/32d5a1bd-67b2-4181-8bc8-bfd4d546b959)

1. Output Panel (Left Side): Displays the LLM or shell command output.
1. History Panel (Right Side): Keeps a chronological list of your actions.  
1. Command Input & Buttons (Bottom-Left): Code, Ask, Search, and Run in Shell specify how your input is interpreted.  Stop cancels the in-progress action.
1. Context Panel (Bottom): Lists active code/text fragments in your current context, specifying whether they’re read-only or editable, and has
   buttons to manipulate context.

Note: as you add context, Brokk will automatically include summaries of the most closely-related classes
as determined by a graph analysis of your codebase.  This helps the LLM avoid hallucinations when
reasoning about your code.  You can change the number of classes included in the File menu:
![image](https://github.com/user-attachments/assets/009ab017-1804-4b0c-8845-50395700c1a1)

You can also see `Refresh Code Intelligence` in the above screenshot.  Brokk will automatically
create the code intelligence graph on startup; it will set it to refresh automatically or manually
based on how long that takes.  If it is on manual refresh, this menu item is how you invoke it.

## Primary Actions

- Code: Tells the LLM you want code generation or modification.
- Ask: Ask a question referencing the current context.
- Search: Invokes a specialized agent that looks through your codebase for answers NOT in your current context.
- Run in Shell: Executes any shell command, streaming the output into the Output Panel.
- Stop: Cancels the currently running LLM or shell command.

## Context Panel
- Edit, Read: Decide which files the LLM can modify (editable) or just look at (read-only).
- Summarize: Summarizes the specified classes (declarations and signatures, but no method bodies).
- Drop: Removes snippets you no longer want in context.
- Copy, Paste: Copy snippets to your clipboard or paste external text into Brokk’s context.
  - Stacktraces get special treatment; they will be augmented with the source of referenced methods.
- Symbol Usage: Pick a symbol (class, field, or method) and automatically gather all references into a snippet.

## General Workflow
- Add relevant code or text to your context (choose Edit for modifiable files, Read for reference-only).
- Type instructions in the command box; use Code, Ask, Search, or Run in Shell as needed.
- Capture or incorporate external context using Run combined witn “Capture Text” or “Edit Files.”
- Use the History Panel to keep track, undo, or redo changes. Forget to commit and the LLM scribbled all over your
  code in the next request? No problem, Undo includes filesystem changes too.

# Example Scenarios
Here are three example scenarios illustrating how Brokk helps with real-world tasks.

## Scenario #1: Debugging a Regression with Git Bisect
1. Run `git bisect` to identify the commit that caused a regression.
2. Load the commit and the files changed by that commit as editable context: run `git show [revision]`,
   then `Capture Text` and `Edit References`.  (You can also select the new context fragment in the context table
   and click `Edit Files` from there; `Edit References` is a shortcut.)
4. Paste the stacktrace corresponding to the regression with ctrl-V or the Paste button.
5. Tell the LLM: "This stacktrace is caused by a change in the attached diff. Look at the changes to see what
   could cause the problem, and fix it."

## Scenario #2: Exploring an unfamiliar part of the codebase
1. You want to know how that BM25 search feature your colleague wrote works. Type "how does bm25 search work?" into
   the Instructions area and click Search.
2. The Search output is automatically captured as context; if you want to make changes, select it and click `Edit Files.`

## Scenario #3: Symbol Usage + Ask
![image](https://github.com/user-attachments/assets/e5756f8d-9cef-4467-b3c3-43872eafe8e1)

1. Invoke Symbol Usage on SSTableIndex::orderBy and SSTableIndex::search.  Optionally,
   include SSTableIndex itself as read-only context.
3. Type your instructions ("what is the difference between SSTableIndex::orderBy and SSTableIndex::search?") into the instructions
   area and click Ask.  The LLM answers your questions.

## A note on o1pro

Brokk is particularly useful when making complex, multi-file edits with o1pro.

After setting up your session, use `copy` to pull all the content, including Brokk's prompts, into your clipboard.
Paste into o1pro and add your request at the bottom in the <goal> section.  Then paste o1pro's response back into
Brokk and have it apply the edits with the Code action.

# Current status

We are currently focused on making Brokk's Java support the best in the world.
Other languages will follow.

### Known issues

- Opening different projects is not yet supported, Brokk always works on the project from cwd
  that it was executed from
- "Stop" button does not work during search.  This is caused by https://github.com/langchain4j/langchain4j/issues/2658
- Joern (the code intelligence engine) needs to run delombok before it can analyze anything.
  Delombok is extremely slow for anything but trivial projects, making Brokk a poor fit for
  large Lombok-using codebases.

# Getting started

Requirements: Java 21+

1. `cd /path/to/my/project`
2. `export ANTHROPIC_API_KEY=xxy`
   - or, `export OPENAI_API_KEY=xxy`
   - or, `export DEEPSEEK_API_KEY=xxy`
   - other providers and models are technically supported, making them easier to use is high priority.
     In the meantime, look at Models.java for how to set up a ~/.config/brokk/brokk.yml file with
     your preferred option if the defaults don't work for you.
1. `java -jar /path/to/brokk/brokk-0.1.jar`

Brokk will attempt to infer the build command for your project.  You can edit that in `.brokk/project.properties`.

There is a [Brokk Discord](https://discord.gg/ugXqhRem) for questions and suggestions.

## Finer points on some commands

- Brokk doesn't offer automatic running of tests (too much variance in what you might want it to do).
  Instead, Brokk allows you to run arbitrary shell commands, and import those as context with "Capture Text"
  or "Edit Files."
