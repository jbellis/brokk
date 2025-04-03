Coding Style Guide

1. **Java 21 features**: The codebase leverages Java features up to JDK 21. Embrace the lambdas! and also getFirst/getLast, Collectors.toList, pattern matching for instanceof, records and record patterns, etc.
1. **Prefer functional streams to manual loops**: Leverage streams for transforming collections, joining to Strings, etc.
1. **Favor Immutable Data Structures**: Prefer `List.of` and `Map.of`, as well as the Stream Collectors.
1. **Provide Comprehensive Logging**: Log relevant information using log4j, including request/response details, errors, and other important events.
1. **Use `var`**: Prefer `var` for local variable declarations. Exception: numeric types, such as `int`, `float`, etc.
1. **Avoid StringBuilder**: prefer joins or interpolated text blocks where possible. Use stripIndent() with text blocks.
1. **Multiline parameters**: When calling a method with more parameters than reasonably fit on one line, align as follows:
```
   var combined = Streams.concat(currentContext().readonlyFiles(),
                                 currentContext().virtualFragments(),
                                 Stream.of(currentContext().getAutoContext()))
       .map(this::formattedOrNull)
       .filter(Objects::nonNull)
       .collect(Collectors.joining("\n\n"));
```
  When declaring a method with multiline parameters, align similarly and also put the opening brace on a new line.
1. **Use asserts to validate assumptions**: Use `assert` to validate assumptions, and prefer making reasonable assumptions backed by assert to defensive `if` checks.
2. **Let it throw**: Let unexpected exceptions propagate up where they will be logged by a global handler. Don't catch unless you have context-specific handling to apply.
1. **It's okay to let exceptions bubble up**: If you can't handle an exception, let it bubble up. Don't try to handle it unless we can actually do something about it.
1. **DRY**: Don't Repeat Yourself. Refactor similar code into a common method. But feature flag parameters are a design smell; if you would need to add flags, write separate methods instead.
1. **Live in the present**: comment on the final version of the code, not on deltas. Deltas live in Git.
