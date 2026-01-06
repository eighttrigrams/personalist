# Planning and keeping track of work

In the plans directory, use markdown files there.

Put plans that are done into plans/done. When you do that, leave a note at the top in that file when you did that, what did the trick in fixing the problem, and maybe a linked commit.

# Basic commands

Starting and stopping the server.

```bash
$ make start
$ make stop
```

# Reviewing changes

Changes typically mean a set of modifications to source files which are results
of a single prompt or a series of coherent prompts. Changes may refer to a PR (pull request),
modifications to sources in a branch, or simple staged changes which have not even be commited. 
In all of these cases, changes refer to modifications of source files against the `master` branch.

Whenever a meaningful changeset has been implemented, it is time to review those changes. Such a review
first of all will require to shut down a possibly the running application (the one under development).
Next, we run the tests and verify all is green. 

When the changes involve the UI, we additionally use an existing browser to look at the running application 
(so the application needs to get started) and verify those changes do what they are supposed to. When testing
the UI, make sure to most of the time do "natural" interactions as the user would do, like clicking, instead
of brute-forcing your way using javascript.

Leave the browser open after a UI testing round and invite the user to see for themselves. Give them a reproduction guideline.
