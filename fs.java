import java.io.BufferedReader; //adds readLine()
import java.io.IOException; //gives error instead of crashing when touching host files
import java.io.InputStreamReader; // turns bytes into characters
import java.nio.ByteBuffer; // lets me write 4 byte and 8 byte.
import java.nio.charset.StandardCharsets; // UTF_8 convert file and user to/from bytes.
import java.nio.file.Files; //get files
import java.nio.file.Path; //traverse
import java.nio.file.Paths;
import java.time.Instant; //turns seconds into point in time
import java.time.ZoneId; // my computer time zone
import java.time.format.DateTimeFormatter; //formats time/date
import java.util.ArrayList; 
import java.util.Arrays;
import java.util.List;

public class FS {

    static final int BLOCK_SIZE = 256;
    static final int ENTRY_SIZE = 64;
    static final int ENTRIES_PER_BLOCK = BLOCK_SIZE / ENTRY_SIZE; // 4
    static final int NAME_MAX = 56;
    static final int USER_MAX = 40;
    static final int GROUP_SIZE = 32;
    static final int GROUPS_PER_BLOCK = BLOCK_SIZE / GROUP_SIZE; // 8
    static final int DIRECT = 7; // direct data pointers per group
    static final int CHAIN_SLOT = 7; // slot that chains to the next group
    static final int BITS_PER_BLOCK = BLOCK_SIZE * 8; // 2048
    static final int MAGIC = 0x43504D46; // "CPMF"
    static final int MAX_BLOCKS = 1 << 20;// 256 MB cap

    // superblock field offsets 
    static final int SB_MAGIC = 0, SB_NUM_BLOCKS = 4, SB_BLOCK_SIZE = 8,
            SB_NUM_NAMES = 12, SB_NUM_INODES = 16, SB_NUM_GROUPS = 20,
            SB_BITMAP = 24, SB_FNT = 28, SB_DABPT = 32, SB_BPT = 36,
            SB_DATA = 40, SB_FORMATTED = 44;

    // state
    byte[] raw; // the whole "disk"
    ByteBuffer disk;   // view of raw for getInt/putInt
    int numBlocks, numNames, numInodes, numGroups;
    int bitmapStart, fntStart, dabptStart, bptStart, dataStart;
    boolean formatted = false;
    String currentUser = "root";

    int fntPos(int i)   { return fntStart * BLOCK_SIZE + i * ENTRY_SIZE; }
    int inodePos(int i) { return dabptStart * BLOCK_SIZE + i * ENTRY_SIZE; }
    int groupPos(int g) { return bptStart * BLOCK_SIZE + g * GROUP_SIZE; }

    boolean bitGet(int b) {
        return (raw[bitmapStart * BLOCK_SIZE + b / 8] & (1 << (b % 8))) != 0;
    }

    void bitSet(int b, boolean used) {
        int idx = bitmapStart * BLOCK_SIZE + b / 8;
        if (used) raw[idx] |= (byte) (1 << (b % 8));
        else      raw[idx] &= (byte) ~(1 << (b % 8));
    }

    void zero(int pos, int len) { Arrays.fill(raw, pos, pos + len, (byte) 0); }

    void writeStr(int pos, String s, int max) {
        zero(pos, max);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, raw, pos, Math.min(b.length, max));
    }

    String readStr(int pos, int max) {
        int n = 0;
        while (n < max && raw[pos + n] != 0) n++;
        return new String(raw, pos, n, StandardCharsets.UTF_8);
    }

    static int ceilDiv(long a, long b) { return (int) ((a + b - 1) / b); }

    static void err(String msg) { System.out.println("Error: " + msg); }

    boolean needFormatted() {
        if (raw == null) { err("no file system. Use createfs or openfs first."); return false; }
        if (!formatted)  { err("file system not formatted. Use formatfs first."); return false; }
        return true;
    }
    // Createfs / Formatfs / Savefs / Openfs
    void createfs(int n) {
        if (n < 4 || n > MAX_BLOCKS) { err("#blocks must be between 4 and " + MAX_BLOCKS); return; }
        raw = new byte[n * BLOCK_SIZE];
        disk = ByteBuffer.wrap(raw);
        numBlocks = n;
        formatted = false;
        disk.putInt(SB_MAGIC, MAGIC);
        disk.putInt(SB_NUM_BLOCKS, n);
        disk.putInt(SB_BLOCK_SIZE, BLOCK_SIZE);
        disk.putInt(SB_FORMATTED, 0);
        System.out.println("Created disk: " + n + " blocks x " + BLOCK_SIZE + " bytes = "
                + (long) n * BLOCK_SIZE + " bytes");
    }

    void formatfs(int names, int inodes, int groups) {
        if (raw == null) { err("no disk. Use createfs or openfs first."); return; }
        if (names < 1 || inodes < 1 || groups < 1) { err("all sizes must be at least 1"); return; }

        long bitmapBlocks = ceilDiv(numBlocks, BITS_PER_BLOCK);
        long fntBlocks    = ceilDiv(names, ENTRIES_PER_BLOCK);
        long dabptBlocks  = ceilDiv(inodes, ENTRIES_PER_BLOCK);
        long bptBlocks    = ceilDiv(groups, GROUPS_PER_BLOCK);
        long meta = 1 + bitmapBlocks + fntBlocks + dabptBlocks + bptBlocks;
        if (meta >= numBlocks) {
            err("metadata needs " + meta + " blocks but the disk only has " + numBlocks
                    + " (need at least one data block)");
            return;
        }

        Arrays.fill(raw, (byte) 0);
        numNames = names; numInodes = inodes; numGroups = groups;
        bitmapStart = 1;
        fntStart    = bitmapStart + (int) bitmapBlocks;
        dabptStart  = fntStart + (int) fntBlocks;
        bptStart    = dabptStart + (int) dabptBlocks;
        dataStart   = bptStart + (int) bptBlocks;

        disk.putInt(SB_MAGIC, MAGIC);
        disk.putInt(SB_NUM_BLOCKS, numBlocks);
        disk.putInt(SB_BLOCK_SIZE, BLOCK_SIZE);
        disk.putInt(SB_NUM_NAMES, numNames);
        disk.putInt(SB_NUM_INODES, numInodes);
        disk.putInt(SB_NUM_GROUPS, numGroups);
        disk.putInt(SB_BITMAP, bitmapStart);
        disk.putInt(SB_FNT, fntStart);
        disk.putInt(SB_DABPT, dabptStart);
        disk.putInt(SB_BPT, bptStart);
        disk.putInt(SB_DATA, dataStart);
        disk.putInt(SB_FORMATTED, 1);

        for (int i = 0; i < numNames; i++) disk.putInt(fntPos(i) + NAME_MAX, -1);
        for (int b = 0; b < dataStart; b++) bitSet(b, true);   // metadata blocks are in use
        formatted = true;

        System.out.println("Formatted: " + names + " file names, " + inodes + " DABPT entries, "
                + groups + " BPT groups");
        System.out.println("  metadata blocks: " + dataStart + "  data blocks: " + (numBlocks - dataStart));
    }

    void savefs(String name) {
        if (raw == null) { err("no disk to save."); return; }
        try {
            Files.write(Paths.get(name), raw);
            System.out.println("Saved " + raw.length + " bytes to '" + name + "'");
        } catch (IOException e) {
            err("cannot write '" + name + "': " + e.getMessage());
        }
    }

    void openfs(String name) {
        byte[] data;
        try {
            data = Files.readAllBytes(Paths.get(name));
        } catch (IOException e) {
            err("cannot read '" + name + "': " + e.getMessage());
            return;
        }
        if (data.length < 48 || data.length % BLOCK_SIZE != 0) { err("not a valid disk image"); return; }
        ByteBuffer bb = ByteBuffer.wrap(data);
        if (bb.getInt(SB_MAGIC) != MAGIC || bb.getInt(SB_BLOCK_SIZE) != BLOCK_SIZE
                || bb.getInt(SB_NUM_BLOCKS) * (long) BLOCK_SIZE != data.length) {
            err("not a valid disk image (bad superblock)");
            return;
        }
        raw = data;
        disk = bb;
        numBlocks = disk.getInt(SB_NUM_BLOCKS);
        formatted = disk.getInt(SB_FORMATTED) == 1;
        if (formatted) {
            numNames    = disk.getInt(SB_NUM_NAMES);
            numInodes   = disk.getInt(SB_NUM_INODES);
            numGroups   = disk.getInt(SB_NUM_GROUPS);
            bitmapStart = disk.getInt(SB_BITMAP);
            fntStart    = disk.getInt(SB_FNT);
            dabptStart  = disk.getInt(SB_DABPT);
            bptStart    = disk.getInt(SB_BPT);
            dataStart   = disk.getInt(SB_DATA);
        }
        System.out.println("Opened '" + name + "': " + numBlocks + " blocks"
                + (formatted ? "" : " (not formatted)"));
    }

    // Searching / allocation

    boolean fntUsed(int i) { return raw[fntPos(i)] != 0; }

    int findName(String name) {
        for (int i = 0; i < numNames; i++)
            if (fntUsed(i) && readStr(fntPos(i), NAME_MAX).equals(name)) return i;
        return -1;
    }

    int findFreeFnt() {
        for (int i = 0; i < numNames; i++) if (!fntUsed(i)) return i;
        return -1;
    }

    int findFreeInode() {
        for (int i = 0; i < numInodes; i++) if (disk.getInt(inodePos(i) + 16) == 0) return i;
        return -1;
    }

    /* Returns n free data block numbers, or null if there aren't enough. */
    int[] findFreeBlocks(int n) {
        int[] out = new int[n];
        int got = 0;
        for (int b = dataStart; b < numBlocks && got < n; b++)
            if (!bitGet(b)) out[got++] = b;
        return got == n ? out : null;
    }

    /* Returns n free BPT group indexes, or null. A group is free when its slot 0 is 0. */
    int[] findFreeGroups(int n) {
        int[] out = new int[n];
        int got = 0;
        for (int g = 0; g < numGroups && got < n; g++)
            if (disk.getInt(groupPos(g)) == 0) out[got++] = g;
        return got == n ? out : null;
    }

    int countFreeBlocks() {
        int c = 0;
        for (int b = dataStart; b < numBlocks; b++) if (!bitGet(b)) c++;
        return c;
    }

    // Put / Get
    void put(String path) {
        if (!needFormatted()) return;
        Path p = Paths.get(path);
        byte[] data;
        try {
            data = Files.readAllBytes(p);
        } catch (IOException e) {
            err("cannot read '" + path + "': " + e.getMessage());
            return;
        }
        String name = p.getFileName().toString();
        if (name.getBytes(StandardCharsets.UTF_8).length > NAME_MAX) {
            err("file name longer than " + NAME_MAX + " bytes");
            return;
        }

        int nBlocks = ceilDiv(data.length, BLOCK_SIZE);
        int nGroups = ceilDiv(nBlocks, DIRECT);

        // Check that EVERYTHING fits before touching the disk.
        int fnt = findFreeFnt();
        if (fnt < 0) { err("no free file-name entries"); return; }
        int ino = findFreeInode();
        if (ino < 0) { err("no free DABPT entries"); return; }
        int[] blocks = findFreeBlocks(nBlocks);
        if (blocks == null) { err("not enough free blocks (need " + nBlocks + ", have " + countFreeBlocks() + ")"); return; }
        int[] groups = findFreeGroups(nGroups);
        if (groups == null) { err("not enough free BPT groups (need " + nGroups + ")"); return; }

        // Data blocks
        for (int i = 0; i < nBlocks; i++) {
            int b = blocks[i];
            bitSet(b, true);
            zero(b * BLOCK_SIZE, BLOCK_SIZE);
            int off = i * BLOCK_SIZE;
            System.arraycopy(data, off, raw, b * BLOCK_SIZE, Math.min(BLOCK_SIZE, data.length - off));
        }
        // BPT groups, chained
        for (int g = 0; g < nGroups; g++) {
            int pos = groupPos(groups[g]);
            zero(pos, GROUP_SIZE);
            for (int s = 0; s < DIRECT; s++) {
                int idx = g * DIRECT + s;
                if (idx < nBlocks) disk.putInt(pos + 4 * s, blocks[idx]);
            }
            disk.putInt(pos + 4 * CHAIN_SLOT, g + 1 < nGroups ? groups[g + 1] : -1);
        }
        // DABPT entry
        int ip = inodePos(ino);
        zero(ip, ENTRY_SIZE);
        disk.putInt(ip, data.length);
        disk.putLong(ip + 4, System.currentTimeMillis() / 1000);
        disk.putInt(ip + 12, nGroups > 0 ? groups[0] : -1);
        disk.putInt(ip + 16, 1);
        writeStr(ip + 20, currentUser, USER_MAX);
        // FNT entry
        int fp = fntPos(fnt);
        zero(fp, ENTRY_SIZE);
        writeStr(fp, name, NAME_MAX);
        disk.putInt(fp + NAME_MAX, ino);

        System.out.println("Stored '" + name + "' (" + data.length + " bytes, " + nBlocks
                + " blocks, owner " + currentUser + ")");
    }

    void get(String name, String hostPath) {
        if (!needFormatted()) return;
        int f = findName(name);
        if (f < 0) { err("no such file: " + name); return; }
        int ip = inodePos(disk.getInt(fntPos(f) + NAME_MAX));
        int size = disk.getInt(ip);
        byte[] out = new byte[size];
        int written = 0;
        int g = disk.getInt(ip + 12);
        while (g != -1 && written < size) {
            int pos = groupPos(g);
            for (int s = 0; s < DIRECT && written < size; s++) {
                int blk = disk.getInt(pos + 4 * s);
                if (blk == 0) break;
                int len = Math.min(BLOCK_SIZE, size - written);
                System.arraycopy(raw, blk * BLOCK_SIZE, out, written, len);
                written += len;
            }
            g = disk.getInt(pos + 4 * CHAIN_SLOT);
        }
        if (written != size) { err("file is corrupt (block chain ended early)"); return; }
        try {
            Files.write(Paths.get(hostPath), out);
            System.out.println("Copied '" + name + "' (" + size + " bytes) to host file '" + hostPath + "'");
        } catch (IOException e) {
            err("cannot write '" + hostPath + "': " + e.getMessage());
        }
    }

    // Remove / Rename / Link
    /* Remove/Unlink same operation. drop one name, free the file when the last name goes. */
    void remove(String name) {
        if (!needFormatted()) return;
        int f = findName(name);
        if (f < 0) { err("no such file: " + name); return; }
        int fp = fntPos(f);
        int ino = disk.getInt(fp + NAME_MAX);
        zero(fp, ENTRY_SIZE);
        disk.putInt(fp + NAME_MAX, -1);

        int ip = inodePos(ino);
        int links = disk.getInt(ip + 16) - 1;
        if (links > 0) {
            disk.putInt(ip + 16, links);
            System.out.println("Removed name '" + name + "' (" + links + " other link(s) remain)");
            return;
        }
        int g = disk.getInt(ip + 12);
        while (g != -1) {
            int pos = groupPos(g);
            int next = disk.getInt(pos + 4 * CHAIN_SLOT);
            for (int s = 0; s < DIRECT; s++) {
                int blk = disk.getInt(pos + 4 * s);
                if (blk != 0) { zero(blk * BLOCK_SIZE, BLOCK_SIZE); bitSet(blk, false); }
            }
            zero(pos, GROUP_SIZE);
            g = next;
        }
        zero(ip, ENTRY_SIZE);
        System.out.println("Removed '" + name + "'");
    }

    void rename(String oldName, String newName) {
        if (!needFormatted()) return;
        if (newName.getBytes(StandardCharsets.UTF_8).length > NAME_MAX) { err("new name too long"); return; }
        int f = findName(oldName);
        if (f < 0) { err("no such file: " + oldName); return; }
        if (findName(newName) >= 0) { err("'" + newName + "' already exists"); return; }
        writeStr(fntPos(f), newName, NAME_MAX);
        System.out.println("Renamed '" + oldName + "' to '" + newName + "'");
    }

    void link(String existing, String newName) {
        if (!needFormatted()) return;
        if (newName.getBytes(StandardCharsets.UTF_8).length > NAME_MAX) { err("new name too long"); return; }
        int f = findName(existing);
        if (f < 0) { err("no such file: " + existing); return; }
        if (findName(newName) >= 0) { err("'" + newName + "' already exists"); return; }
        int free = findFreeFnt();
        if (free < 0) { err("no free file-name entries"); return; }
        int ino = disk.getInt(fntPos(f) + NAME_MAX);
        int fp = fntPos(free);
        zero(fp, ENTRY_SIZE);
        writeStr(fp, newName, NAME_MAX);
        disk.putInt(fp + NAME_MAX, ino);
        int ip = inodePos(ino);
        disk.putInt(ip + 16, disk.getInt(ip + 16) + 1);
        System.out.println("Linked '" + newName + "' -> '" + existing + "' (" + disk.getInt(ip + 16) + " links)");
    }

    // List
    void list() {
        if (raw == null) { err("no file system. Use createfs or openfs first."); return; }
        if (!formatted) {
            System.out.println("Disk: " + numBlocks + " blocks x " + BLOCK_SIZE + " bytes (not formatted)");
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        System.out.printf("%-24s %8s %-12s %-19s %5s %5s%n", "NAME", "SIZE", "OWNER", "MODIFIED", "LINKS", "INODE");
        int files = 0;
        for (int i = 0; i < numNames; i++) {
            if (!fntUsed(i)) continue;
            files++;
            int ino = disk.getInt(fntPos(i) + NAME_MAX);
            int ip = inodePos(ino);
            System.out.printf("%-24s %8d %-12s %-19s %5d %5d%n",
                    readStr(fntPos(i), NAME_MAX), disk.getInt(ip), readStr(ip + 20, USER_MAX),
                    fmt.format(Instant.ofEpochSecond(disk.getLong(ip + 4))), disk.getInt(ip + 16), ino);
        }
        int freeInodes = 0, freeGroups = 0;
        for (int i = 0; i < numInodes; i++) if (disk.getInt(inodePos(i) + 16) == 0) freeInodes++;
        for (int g = 0; g < numGroups; g++) if (disk.getInt(groupPos(g)) == 0) freeGroups++;
        System.out.println("--");
        System.out.println("Files: " + files + "   Current user: " + currentUser);
        System.out.println("Disk: " + numBlocks + " blocks x " + BLOCK_SIZE + " bytes");
        System.out.println("Layout (block numbers): superblock 0, bitmap " + bitmapStart + ", FNT " + fntStart
                + ", DABPT " + dabptStart + ", BPT " + bptStart + ", data " + dataStart + ".." + (numBlocks - 1));
        System.out.println("Free: " + countFreeBlocks() + "/" + (numBlocks - dataStart) + " data blocks, "
                + (numNames - files) + "/" + numNames + " names, "
                + freeInodes + "/" + numInodes + " DABPT entries, "
                + freeGroups + "/" + numGroups + " BPT groups");
    }

    // Command loop
    static final String HELP =
            "Commands (case-insensitive):\n"
          + "  createfs #blocks               create a new disk of #blocks x 256 bytes\n"
          + "  formatfs #names #inodes [#bpt] format it (#bpt defaults to 4 x #inodes)\n"
          + "  savefs name                    save the disk image to a host file\n"
          + "  openfs name                    load a disk image from a host file\n"
          + "  list                           list files and disk information\n"
          + "  put hostfile                   copy a host file into the FS\n"
          + "  get fsname [hostfile]          copy an FS file out to the host\n"
          + "  remove name                    remove a file (alias: unlink)\n"
          + "  rename old new                 rename a file\n"
          + "  link existing newname          add another name for a file\n"
          + "  user name                      set the owner for files you put\n"
          + "  help, exit";

    static Integer num(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { err("'" + s + "' is not a number"); return null; }
    }

    void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("CP/M-style file system. Type 'help' for commands.");
        while (true) {
            System.out.print("fs> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) break;
            String[] t = line.trim().split("\\s+");
            if (t[0].isEmpty()) continue;
            String cmd = t[0].toLowerCase();
            int n = t.length - 1;   // number of arguments

            switch (cmd) {
                case "createfs" -> {
                    if (n != 1) { err("usage: createfs #blocks"); break; }
                    Integer a = num(t[1]);
                    if (a != null) createfs(a);
                }
                case "formatfs" -> {
                    if (n < 2 || n > 3) { err("usage: formatfs #names #inodes [#bpt]"); break; }
                    Integer a = num(t[1]), b = num(t[2]);
                    Integer c = n == 3 ? num(t[3]) : (b == null ? null : Integer.valueOf(Math.max(1, b * 4)));
                    if (a != null && b != null && c != null) formatfs(a, b, c);
                }
                case "savefs" -> { if (n != 1) err("usage: savefs name"); else savefs(t[1]); }
                case "openfs" -> { if (n != 1) err("usage: openfs name"); else openfs(t[1]); }
                case "list", "ls" -> list();
                case "put" -> { if (n != 1) err("usage: put hostfile"); else put(t[1]); }
                case "get" -> {
                    if (n < 1 || n > 2) err("usage: get fsname [hostfile]");
                    else get(t[1], n == 2 ? t[2] : t[1]);
                }
                case "remove", "rm", "unlink" -> { if (n != 1) err("usage: remove name"); else remove(t[1]); }
                case "rename", "mv" -> { if (n != 2) err("usage: rename old new"); else rename(t[1], t[2]); }
                case "link" -> { if (n != 2) err("usage: link existing newname"); else link(t[1], t[2]); }
                case "user" -> {
                    if (n != 1) err("usage: user name");
                    else if (t[1].getBytes(StandardCharsets.UTF_8).length > USER_MAX) err("user name longer than " + USER_MAX);
                    else { currentUser = t[1]; System.out.println("Current user: " + currentUser); }
                }
                case "help", "?" -> System.out.println(HELP);
                case "exit", "quit" -> { return; }
                default -> err("unknown command '" + t[0] + "'. Type 'help'.");
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new FS().run();
    }
}
