/* Host test for the pure parts of native/argkey.c (the hook is Linux-only). */
#include "argkey.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;
#define CHECK(c)                                                         \
    do {                                                                 \
        if (!(c)) {                                                      \
            fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #c); \
            failures++;                                                  \
        }                                                                \
    } while (0)

static int streq(const char *a, const char *b) { return a && b && strcmp(a, b) == 0; }

static void test_build(void)
{
    char *argv[] = {"soloist", "-n", "ST15i", NULL};
    char *envp[] = {"HOME=/h", "A=b", NULL};
    char key[] = "secret";
    int n = -1;
    char **out = argkey_build(3, argv, envp, key, &n);
    CHECK(out != NULL);
    CHECK(n == 5);
    CHECK(streq(out[0], "soloist"));
    CHECK(streq(out[1], "-k"));
    CHECK(out[2] == key);
    CHECK(streq(out[3], "-n"));
    CHECK(streq(out[4], "ST15i"));
    CHECK(out[5] == NULL);
    /* environ = &argv[argc + 1] must be the environment. */
    CHECK(streq(out[n + 1], "HOME=/h"));
    CHECK(streq(out[n + 2], "A=b"));
    CHECK(out[n + 3] == NULL);
    free(out);
}

static void test_build_argv0_only_empty_env(void)
{
    char *argv[] = {"soloist", NULL};
    char *envp[] = {NULL};
    char key[] = "k";
    int n;
    char **out = argkey_build(1, argv, envp, key, &n);
    CHECK(n == 3);
    CHECK(streq(out[1], "-k") && out[2] == key && out[3] == NULL && out[4] == NULL);
    free(out);
}

static void test_take(void)
{
    char e0[] = "HOME=/h";
    char e1[] = "OVERHEAR_SOLOIST_KEY=abc123";
    char e2[] = "OVERHEAR_SOLOIST_KEYX=other";
    char e3[] = "OVERHEAR_SOLOIST_KEY=dup";
    char e4[] = "Z=1";
    size_t l1 = strlen(e1), l3 = strlen(e3);
    char *envp[] = {e0, e1, e2, e3, e4, NULL};
    char *key = argkey_take(envp);
    CHECK(streq(key, "abc123"));
    CHECK(envp[0] == e0 && envp[1] == e2 && envp[2] == e4 && envp[3] == NULL);
    for (size_t i = 0; i < l1; i++)
        CHECK(e1[i] == 0);
    for (size_t i = 0; i < l3; i++)
        CHECK(e3[i] == 0);
    free(key);
}

static void test_take_first_and_absent(void)
{
    char e0[] = "OVERHEAR_SOLOIST_KEY=";
    char e1[] = "A=b";
    char *envp[] = {e0, e1, NULL};
    char *key = argkey_take(envp);
    CHECK(streq(key, ""));
    CHECK(envp[0] == e1 && envp[1] == NULL);
    free(key);
    CHECK(argkey_take(envp) == NULL);
    CHECK(envp[0] == e1);
    char *empty[] = {NULL};
    CHECK(argkey_take(empty) == NULL);
}

int main(void)
{
    test_build();
    test_build_argv0_only_empty_env();
    test_take();
    test_take_first_and_absent();
    if (failures) {
        fprintf(stderr, "argkey_test: %d failure(s)\n", failures);
        return 1;
    }
    printf("argkey_test: OK\n");
    return 0;
}
