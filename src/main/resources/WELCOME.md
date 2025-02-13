Brokk is code intelligence for AI.

Check out the README at https://github.com/jbellis/brokk, but
here's the short version:

Use the `/add` command to add files that you want Brokk to edit. You can
use `/readonly` to add files that are related to your problem but you don't
want edited; you can also `/summarize` a file.

Use `/stacktrace` and `/usage` to add source from code intelligence.

Use `$$` to pull in information from another tool (most often git), and
`/paste` to pull in information from your clipboard.

/add, /read, and /summ also compose with non-file fragments. `/add 1`
will add all the files referenced in fragment 1 for editing, whether
fragment 1 comes from `$$` or `/usage` or anything else.

Whatever you type without a slash command will be sent to the AI as
instructions. Brokk will try to build your project after applying its
edits, and iterate to fix any errors.

Finally, you can trigger completion suggestions of files, classnames,
etc. for any command with tab or ctrl-space.

P.S. `/copy` pulls all the context into your clipboard with editing
instructions for o1pro.
