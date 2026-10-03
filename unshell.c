// Llyween Hunt
// 3320-002
// 3 (Lab)
// decided to find prexisting examples for a refererence so I'm not stuck looking at documentation

#define _DEFAULT_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <ctype.h>
#include <time.h>
#include <dirent.h>
#include <sys/stat.h>

#ifndef NAME_MAX
#define NAME_MAX 255
#endif
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define MAX_ENTRIES 1024
#define PAGE_SIZE   10
#define INPUT_LEN   4096

// Which kinds of entries a selection may match 
#define ANY      0
#define FILE_ONLY 1
#define DIR_ONLY  2

typedef struct {
    char   name[NAME_MAX + 1];
    off_t  size;
    time_t mtime;
    mode_t mode;
    int    is_dir;
} Entry;

static Entry entries[MAX_ENTRIES];
static int   count = 0;
static int   page = 0;
static char sort_mode = 'N';   // N = name, S = size, D = date 


// Read a line, strip trailing whitespace. Returns 0 on EOF. 
static int read_line(char *buf, size_t n)
{
    if (!fgets(buf, n, stdin))
        return 0;
    size_t l = strlen(buf);
    while (l > 0 && isspace((unsigned char)buf[l - 1]))
        buf[--l] = '\0';
    return 1;
}

// Return rest-of-line argument p if present, otherwise prompt for it. 
static char *need_arg(char *p, const char *prompt, char *buf, size_t n)
{
    if (*p)
        return p;
    printf("%s", prompt);
    fflush(stdout);
    if (!read_line(buf, n))
        return NULL;
    char *q = buf;
    while (isspace((unsigned char)*q))
        q++;
    return q;
}


static int compare(const void *a, const void *b)
{
    const Entry *x = a, *y = b;
    if (x->is_dir != y->is_dir)
        return x->is_dir - y->is_dir;            // files first, then dirs 
    if (sort_mode == 'S' && x->size != y->size)
        return x->size < y->size ? 1 : -1;     // bigger files first 
    if (sort_mode == 'D' && x->mtime != y->mtime)
        return x->mtime < y->mtime ? 1 : -1;   // newer files first 
    return strcmp(x->name, y->name);
}

static void sort_entries(void)
{
    qsort(entries, count, sizeof(Entry), compare);
}

// One pass through the current directory; stores everything in the array. 
static int load_dir(void)
{
    DIR *d = opendir(".");
    if (!d) {
        perror("opendir");
        return -1;
    }
    count = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0)
            continue;
        if (count >= MAX_ENTRIES) {
            fprintf(stderr, "Warning: more than %d entries, extra ones ignored.\n",
                    MAX_ENTRIES);
            break;
        }
        struct stat st;
        if (stat(de->d_name, &st) != 0 && lstat(de->d_name, &st) != 0)
            continue;                            // vanished or unreadable 
        Entry *e = &entries[count++];
        strncpy(e->name, de->d_name, NAME_MAX);
        e->name[NAME_MAX] = '\0';
        e->size   = st.st_size;
        e->mtime  = st.st_mtime;
        e->mode   = st.st_mode;
        e->is_dir = S_ISDIR(st.st_mode) ? 1 : 0;
    }
    closedir(d);
    page = 0;
    sort_entries();
    return 0;
}

//DISPLAY

static void show_screen(void)
{
    char cwd[PATH_MAX], tbuf[64];
    if (!getcwd(cwd, sizeof cwd))
        strcpy(cwd, "?");
    time_t now = time(NULL);
    strftime(tbuf, sizeof tbuf, "%d %B %Y, %I:%M %p", localtime(&now));

    int pages = (count + PAGE_SIZE - 1) / PAGE_SIZE;
    if (pages == 0) pages = 1;
    if (page >= pages) page = pages - 1;
    int start = page * PAGE_SIZE;
    int end = start + PAGE_SIZE < count ? start + PAGE_SIZE : count;

    printf("\nCurrent Working Dir: %s\n", cwd);
    printf("It is now: %s\n", tbuf);
    printf("Page %d of %d  (%d entries)\n", page + 1, pages, count);

    int shown_f = 0, shown_d = 0;
    for (int i = start; i < end; i++) {
        Entry *e = &entries[i];
        if (!e->is_dir && !shown_f) { printf("Files:\n"); shown_f = 1; }
        if (e->is_dir && !shown_d)  { printf("Directories:\n"); shown_d = 1; }
        char dbuf[32];
        strftime(dbuf, sizeof dbuf, "%Y-%m-%d %H:%M", localtime(&e->mtime));
        printf("  %3d. %-30s %10lld  %s\n", i, e->name, (long long)e->size, dbuf);
    }
    printf("Operation: D Display   C Change Directory   M Move to Directory\n"
           "           S Sort      X Remove File\n"
           "           N Next page P Prev page   Q Quit\n");
    printf("(Type the key, then a number or partial name, e.g. \"D 2\" or \"C my\")\n");
}

//SELECTING ENTRY

static int kind_ok(int i, int kind)
{
    return kind == ANY || (kind == DIR_ONLY) == entries[i].is_dir;
}

//RETURNS INDEX OR -1 IF FAILURE
static int select_entry(const char *arg, int kind)
{
    if (!*arg) {
        printf("No file or directory given.\n");
        return -1;
    }

    int all_digits = 1;
    for (const char *c = arg; *c; c++)
        if (!isdigit((unsigned char)*c)) all_digits = 0;

    if (all_digits) {
        long n = strtol(arg, NULL, 10);
        if (n < 0 || n >= count) {
            printf("Number %ld is out of range (0-%d).\n", n, count - 1);
            return -1;
        }
        if (!kind_ok((int)n, kind)) {
            printf("Entry %ld is a %s; a %s is needed here.\n", n,
                   entries[n].is_dir ? "directory" : "file",
                   kind == DIR_ONLY ? "directory" : "file");
            return -1;
        }
        return (int)n;
    }

    //name then prefix
    for (int i = 0; i < count; i++)
        if (kind_ok(i, kind) && strcmp(entries[i].name, arg) == 0)
            return i;

    size_t len = strlen(arg);
    int matches = 0, first = -1;
    for (int i = 0; i < count; i++)
        if (kind_ok(i, kind) && strncmp(entries[i].name, arg, len) == 0) {
            if (first < 0) first = i;
            matches++;
        }
    if (matches == 1)
        return first;
    if (matches == 0) {
        printf("No %s matches \"%s\".\n",
               kind == DIR_ONLY ? "directory" : kind == FILE_ONLY ? "file" : "entry", arg);
        return -1;
    }
    printf("\"%s\" is ambiguous. Matches:\n", arg);
    for (int i = 0; i < count; i++)
        if (kind_ok(i, kind) && strncmp(entries[i].name, arg, len) == 0)
            printf("  %d. %s\n", i, entries[i].name);
    return -1;
}

//COMMANDS

static void do_display(char *arg)
{
    int i = select_entry(arg, ANY);
    if (i < 0) return;
    Entry *e = &entries[i];
    char perm[10], tbuf[64];
    const char *rwx = "rwxrwxrwx";
    for (int b = 0; b < 9; b++)
        perm[b] = (e->mode & (1 << (8 - b))) ? rwx[b] : '-';
    perm[9] = '\0';
    strftime(tbuf, sizeof tbuf, "%d %B %Y, %I:%M %p", localtime(&e->mtime));
    printf("Name:        %s\nType:        %s\nSize:        %lld bytes\n"
           "Permissions: %s\nModified:    %s\n",
           e->name, e->is_dir ? "directory" : "file", (long long)e->size, perm, tbuf);
}

static void do_change(char *arg)
{
    int i = select_entry(arg, DIR_ONLY);
    if (i < 0) return;
    if (chdir(entries[i].name) != 0) {
        fprintf(stderr, "Cannot change to '%s': ", entries[i].name);
        perror("");
        return;
    }
    load_dir();
}

static void do_sort(void)
{
    char buf[INPUT_LEN];
    printf("Sort by (S)ize, (D)ate, or (N)ame? ");
    fflush(stdout);
    if (!read_line(buf, sizeof buf)) return;
    char c = (char)toupper((unsigned char)buf[0]);
    if (c != 'S' && c != 'D' && c != 'N') {
        printf("Please answer S, D, or N.\n");
        return;
    }
    sort_mode = c;
    sort_entries();     //resorts array in memory
    page = 0;
}

static void do_move(char *arg)
{
    int i = select_entry(arg, FILE_ONLY);
    if (i < 0) return;

    char buf[INPUT_LEN];
    printf("Move '%s' to which directory (number, name, or path)? ", entries[i].name);
    fflush(stdout);
    if (!read_line(buf, sizeof buf)) return;
    char *dest = buf;
    while (isspace((unsigned char)*dest)) dest++;
    if (!*dest) return;

    struct stat st;
    char destdir[PATH_MAX];
    int all_digits = 1;
    for (char *c = dest; *c; c++)
        if (!isdigit((unsigned char)*c)) all_digits = 0;

    if (!all_digits && stat(dest, &st) == 0 && S_ISDIR(st.st_mode)) {
        snprintf(destdir, sizeof destdir, "%s", dest);
    } else {
        int j = select_entry(dest, DIR_ONLY);
        if (j < 0) return;
        snprintf(destdir, sizeof destdir, "%s", entries[j].name);
    }

    char newpath[PATH_MAX + NAME_MAX + 2];
    snprintf(newpath, sizeof newpath, "%s/%s", destdir, entries[i].name);
    if (access(newpath, F_OK) == 0) {
        printf("'%s' already exists; not overwriting.\n", newpath);
        return;
    }
    if (rename(entries[i].name, newpath) != 0) {
        perror("move failed");
        return;
    }
    printf("Moved to %s\n", newpath);
    load_dir();
}

static void do_remove(char *arg)
{
    int i = select_entry(arg, FILE_ONLY);  //GETS FILES
    if (i < 0) return;
    char buf[INPUT_LEN];
    printf("Really remove '%s'? (y/n) ", entries[i].name);
    fflush(stdout);
    if (!read_line(buf, sizeof buf)) return;
    if (tolower((unsigned char)buf[0]) != 'y') {
        printf("Not removed.\n");
        return;
    }
    if (unlink(entries[i].name) != 0) {      //unlink, simple enough to add
        perror("remove failed");
        return;
    }
    load_dir();                              //refresh
}


int main(int argc, char **argv)
{
    if (argc > 1 && chdir(argv[1]) != 0) {
        fprintf(stderr, "myshell: cannot open '%s': ", argv[1]);
        perror("");
        return 1;
    }
    if (load_dir() != 0)
        return 1;

    char line[INPUT_LEN], argbuf[INPUT_LEN];
    int pages;

    while (1) {
        show_screen();
        printf("Operation: ");
        fflush(stdout);
        if (!read_line(line, sizeof line))
            break;                                  

        char *p = line;
        while (isspace((unsigned char)*p)) p++;
        if (!*p) continue;
        char cmd = (char)toupper((unsigned char)*p++);
        while (isspace((unsigned char)*p)) p++;

        char *a;
        switch (cmd) {
        case 'Q':
            return 0;
        case 'N':
            pages = (count + PAGE_SIZE - 1) / PAGE_SIZE;
            if (page + 1 < pages) page++;
            else printf("Already on the last page.\n");
            break;
        case 'P':
            if (page > 0) page--;
            else printf("Already on the first page.\n");
            break;
        case 'D':
            if ((a = need_arg(p, "Number or name to display: ", argbuf, sizeof argbuf))) do_display(a);
            break;
        case 'C':
            if ((a = need_arg(p, "Directory number or name: ", argbuf, sizeof argbuf))) do_change(a);
            break;
        case 'M':
            if ((a = need_arg(p, "Number or name of file to move: ", argbuf, sizeof argbuf))) do_move(a);
            break;
        case 'S':
            do_sort();
            break;
        case 'X':
            if ((a = need_arg(p, "Number or name of file to remove: ", argbuf, sizeof argbuf))) do_remove(a);
            break;
        default:
            printf("Unknown command '%c'. Use D, C, M, S, X, N, P, or Q.\n", cmd);
        }
    }
    return 0;
}
