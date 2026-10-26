/*
 * libargkey.so: hands Soloist its API key without putting it on the command
 * line. Soloist only accepts the key as "-k <key>", and on Android 4.3 every
 * app can read /proc/<pid>/cmdline. So the app passes the key in the
 * environment (OVERHEAR_SOLOIST_KEY; /proc/<pid>/environ is private to the uid)
 * and preloads this library:
 *
 *   ld-linux-armhf.so.3 --preload libargkey.so --library-path DIR soloist ...
 *
 * The __libc_start_main hook copies the key, wipes the original
 * "OVERHEAR_SOLOIST_KEY=..." bytes (/proc/<pid>/environ reads that memory, so
 * unsetenv alone would leave the key there) and removes the variable. It then
 * calls the real __libc_start_main with a heap argv that has "-k <key>" right
 * after argv[0]. /proc/<pid>/cmdline shows the original stack argv, which never
 * holds the key. With no variable set, argv is passed through unchanged, so
 * Soloist prints its own usage error.
 *
 * glibc's __libc_start_main sets environ = &argv[argc + 1], so the new block
 * is laid out like the kernel's: argv..., NULL, envp..., NULL.
 *
 * The key stays in the heap for the life of the process, by design: Soloist
 * keeps using its argv, and heap memory is only readable through
 * /proc/<pid>/mem, which is limited to the same uid (as is environ).
 *
 * Nothing is logged and nothing else is hooked.
 */
#define _GNU_SOURCE 1
#include "argkey.h"

#include <stdlib.h>
#include <string.h>

char **argkey_build(int argc, char **argv, char **envp, char *key, int *new_argc)
{
    size_t envc = 0;
    while (envp && envp[envc])
        envc++;
    char **out = malloc(sizeof(char *) * ((size_t)argc + 2 + 1 + envc + 1));
    if (!out)
        return NULL;
    size_t n = 0;
    out[n++] = argv[0];
    out[n++] = "-k";
    out[n++] = key;
    for (int i = 1; i < argc; i++)
        out[n++] = argv[i];
    out[n++] = NULL;
    for (size_t i = 0; i < envc; i++)
        out[n++] = envp[i];
    out[n] = NULL;
    *new_argc = argc + 2;
    return out;
}

char *argkey_take(char **envp)
{
    static const char prefix[] = ARGKEY_ENV "=";
    const size_t plen = sizeof(prefix) - 1;
    char *key = NULL;
    size_t i = 0;
    while (envp && envp[i]) {
        char *entry = envp[i];
        if (strncmp(entry, prefix, plen) != 0) {
            i++;
            continue;
        }
        if (!key)
            key = strdup(entry + plen);
        /* Scrub the bytes in place, then drop the entry from the array. */
        memset(entry, 0, strlen(entry));
        size_t j = i;
        do {
            envp[j] = envp[j + 1];
        } while (envp[j++]);
    }
    return key;
}

#ifdef __linux__
#include <dlfcn.h>

extern char **environ;

typedef int (*main_fn)(int, char **, char **);
typedef int (*start_fn)(main_fn, int, char **, void (*)(void), void (*)(void),
                        void (*)(void), void *);

__attribute__((visibility("default"))) int
__libc_start_main(main_fn main, int argc, char **argv, void (*init)(void),
                  void (*fini)(void), void (*rtld_fini)(void), void *stack_end)
{
    start_fn real = (start_fn)dlsym(RTLD_NEXT, "__libc_start_main");
    if (!real)
        abort();
    /* Scrub the key even when argv cannot take it. */
    char *key = argkey_take(environ);
    if (key && argc >= 1 && argv && argv[0]) {
        int new_argc;
        char **block = argkey_build(argc, argv, environ, key, &new_argc);
        if (!block)
            abort();
        environ = &block[new_argc + 1];
        return real(main, new_argc, block, init, fini, rtld_fini, stack_end);
    }
    free(key);
    return real(main, argc, argv, init, fini, rtld_fini, stack_end);
}
#endif
