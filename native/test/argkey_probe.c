/*
 * Device probe for libargkey.so (see argkey_device.sh): prints what main sees
 * and what /proc shows, so the script can compare it with the expectation.
 */
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <unistd.h>

extern char **environ;

static int count_in_file(const char *path, const char *needle)
{
    static char buf[65536];
    FILE *f = fopen(path, "rb");
    if (!f)
        return -1;
    size_t n = fread(buf, 1, sizeof(buf), f);
    fclose(f);
    int c = 0;
    size_t len = strlen(needle);
    for (size_t i = 0; i + len <= n; i++)
        if (memcmp(buf + i, needle, len) == 0)
            c++;
    return c;
}

int main(int argc, char **argv)
{
    const char *key = getenv("PROBE_EXPECT_KEY");
    printf("argc=%d\n", argc);
    for (int i = 0; i < argc; i++)
        printf("argv[%d]=%s\n", i, argv[i]);
    printf("argv[argc]=%s\n", argv[argc] ? "non-null" : "null");
    printf("environ_is_after_argv=%d\n", environ == &argv[argc + 1]);
    printf("getenv_key_var=%s\n", getenv("OVERHEAR_SOLOIST_KEY") ? "set" : "unset");
    printf("getenv_home=%s\n", getenv("HOME") ? getenv("HOME") : "(null)");
    printf("pagesz=%lu sysconf=%ld\n", getauxval(AT_PAGESZ), sysconf(_SC_PAGESIZE));
    if (key) {
        printf("cmdline_key=%d\n", count_in_file("/proc/self/cmdline", key));
        printf("environ_key=%d\n", count_in_file("/proc/self/environ", key));
    }
    printf("environ_var=%d\n", count_in_file("/proc/self/environ", "OVERHEAR_SOLOIST_KEY"));
    return 0;
}
