0.7.0

## Features
- "Decompile Dependency" allows you to easily add the source code of a dependency to Code Intelligence
- Basic context management and Search works better in the absence of Code Intelligence
- Multiple projects may be open concurrently
- Better prompting when copying to an external LLM
- Incremental output parsing improves responsiveness of the UI with fewer flickers
- Add default models for Google Gemini
- Multiple improvements to Git functionality

## Fixes
- Git push works with SSH remotes
- Partial stashing works as intended
- Symbol usage now includes references in return types and references to subclasses
- Fixed regression to undoing changes on filesystem
- Keyboard-hotkey paste into context table works as intended


0.6.3

## Features
- Preview improvements including Quick Edit on right-click
- Add "collapse" trigger to Git panel
- Use new gpt4o model for voice transcription

## Fixes
- Ctrl-enter works again to submit instructions on Linux


0.6.2

## Fixes
- Fix starting Brokk with no Project


# 0.6.1

## Changes
- Clicking on microphone toggles on/off instead of push-to-talk

## Fixes
- Symbol Usages dialog no longer throws NPE


# 0.6.0

## Features
- Add support for macOS system menu bar and keyboard shortcuts
- move stash management to Log tab
- Moved Context panel buttons to menu items + rightclick
- custom component that can mix text + code blocks with syntax highlighting
- Reorganized top UI panel
- Prompt to add Brokk project files to git

## Fixes
- Reworked history system to avoid race conditions
- Migrate from DirectoryWatcher to WatchService to fix missing events
- "Welcome Back" message on startup works as designed


# 0.5.0

## Features
- Git log and stash
- Search in fragment preview
- Add individual references to context

## Fixes
- AutoContext builds asynchronously now instead of blocking operations in large codebases
- Symbol Usage autocomplete is more intutive now


# 0.4.2

## Features
- Light + Dark themes

## Fixes
- Fix bad interaction between search summarization and prefix compression


# 0.4.1

## Features
- Multi-project support
- Workspace UI layout and current context are saved and restored 

## Fixes
- Improved symbol name completion for Find Usages


# 0.4.0
## Features
- Swing UI
- Search is even better

## Fixes
- Too many to list


# 0.3.0

## Features
- New agentic /search command offers the most powerful code RAG anywhere

## Fixes
- Corner cases of /usages


# 0.2.1

## Features
- Anthropic models default to sonnet 3.7
- Brokk's build migrated to SBT


# 0.2.0

## Features
- Significantly better ranking of classes for Auto context

## Fixes
- Multiple improvements to CPG creation at startup


# 0.1.1

## Features
- Enhanced `/undo` to revert changes to file contents as well as context
- Added `/redo` command for redoing changes

## Fixes
- Summaries now include inner classes
- Improved output capture by removing terminal codes
