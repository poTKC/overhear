/* Internals of libargkey.so (native/argkey.c), exposed for native/test. */
#ifndef ARGKEY_H
#define ARGKEY_H

#define ARGKEY_ENV "OVERHEAR_SOLOIST_KEY"

/*
 * Returns a malloc'd block {argv[0], "-k", key, argv[1..argc-1], NULL,
 * envp..., NULL} and sets *new_argc = argc + 2; NULL if out of memory.
 */
char **argkey_build(int argc, char **argv, char **envp, char *key, int *new_argc);

/*
 * Finds ARGKEY_ENV in envp, returns a strdup'd copy of its value (NULL when
 * absent), zeroes every matching entry's bytes and removes them from envp.
 */
char *argkey_take(char **envp);

#endif
